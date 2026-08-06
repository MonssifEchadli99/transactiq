package io.github.monssifechadli99.transactiq.investigation_assistant.application;

import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.AnswerGenerationUnavailableException;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.ChatGenerationPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.ChatGenerationRequest;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GeneratedAnswerDraft;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GeneratedFindingDraft;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.FocalEvidenceNotFoundException;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GroundedFinding;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GroundingCitation;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GroundingSource;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GroundingStatus;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.InvestigationAnswerResult;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.InvestigationRetrievalResult;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.RetrievedSource;
import io.github.monssifechadli99.transactiq.observability.PortfolioMetrics;
import io.github.monssifechadli99.transactiq.observability.PortfolioMetrics.Signal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds advisory answers from 6A retrieval only, then enforces the citation allow-list before
 * returning any generated content.
 */
public final class InvestigationAnswerService {

    private static final int MAX_RELATED_CASES = 5;

    private final InvestigationRetrievalService retrievalService;
    private final ChatGenerationPort generationPort;
    private final PortfolioMetrics metrics;

    public InvestigationAnswerService(
            InvestigationRetrievalService retrievalService,
            ChatGenerationPort generationPort) {
        this(retrievalService, generationPort, PortfolioMetrics.noop());
    }

    public InvestigationAnswerService(
            InvestigationRetrievalService retrievalService,
            ChatGenerationPort generationPort,
            PortfolioMetrics metrics) {
        this.retrievalService = retrievalService;
        this.generationPort = generationPort;
        this.metrics = metrics;
    }

    public InvestigationAnswerResult answer(String caseId, String question) {
        try {
            InvestigationAnswerResult result = doAnswer(caseId, question);
            metrics.increment(switch (result.groundingStatus()) {
                case GROUNDED -> Signal.INVESTIGATION_ANSWER_GROUNDED;
                case INSUFFICIENT_EVIDENCE -> Signal.INVESTIGATION_ANSWER_INSUFFICIENT;
            });
            return result;
        } catch (FocalEvidenceNotFoundException failure) {
            metrics.increment(Signal.INVESTIGATION_ANSWER_MISSING);
            throw failure;
        } catch (RuntimeException failure) {
            metrics.increment(Signal.INVESTIGATION_ANSWER_UNAVAILABLE);
            throw failure;
        }
    }

    private InvestigationAnswerResult doAnswer(String caseId, String question) {
        InvestigationRetrievalResult retrieval =
                retrievalService.retrieve(caseId, question, MAX_RELATED_CASES);
        List<GroundingSource> sources = toGroundingSources(retrieval);
        Map<String, GroundingSource> sourceById = indexSources(sources);

        GeneratedAnswerDraft draft;
        try {
            draft = generationPort.generate(new ChatGenerationRequest(caseId, question, sources));
        } catch (AnswerGenerationUnavailableException exception) {
            throw exception;
        } catch (RuntimeException ignored) {
            // Provider exceptions can retain requests or response bodies. Replace them without a cause.
            throw unavailable();
        }
        return validateAndResolve(caseId, draft, sourceById);
    }

    private static List<GroundingSource> toGroundingSources(InvestigationRetrievalResult retrieval) {
        List<GroundingSource> sources = new ArrayList<>();
        retrieval.focalSources().forEach(source -> sources.add(toGroundingSource(source)));
        retrieval.relatedCases().forEach(group ->
                group.sources().forEach(source -> sources.add(toGroundingSource(source))));
        return List.copyOf(sources);
    }

    private static GroundingSource toGroundingSource(RetrievedSource source) {
        return new GroundingSource(
                source.sourceId(), source.sourceType(), source.caseId(), source.excerpt());
    }

    private static Map<String, GroundingSource> indexSources(List<GroundingSource> sources) {
        Map<String, GroundingSource> sourceById = new LinkedHashMap<>();
        for (GroundingSource source : sources) {
            if (!hasText(source.sourceId()) || source.sourceType() == null || !hasText(source.caseId())
                    || source.text() == null || sourceById.putIfAbsent(source.sourceId(), source) != null) {
                throw unavailable();
            }
        }
        return Map.copyOf(sourceById);
    }

    private static InvestigationAnswerResult validateAndResolve(
            String caseId,
            GeneratedAnswerDraft draft,
            Map<String, GroundingSource> sourceById) {
        if (draft == null || !hasText(draft.summary()) || draft.groundingStatus() == null
                || draft.findings() == null || draft.recommendedChecks() == null
                || draft.recommendedChecks().isEmpty()
                || draft.recommendedChecks().stream().anyMatch(check -> !hasText(check))) {
            throw unavailable();
        }

        if (draft.groundingStatus() == GroundingStatus.INSUFFICIENT_EVIDENCE) {
            if (!draft.findings().isEmpty()) {
                throw unavailable();
            }
            return new InvestigationAnswerResult(
                    caseId,
                    draft.summary().strip(),
                    List.of(),
                    stripChecks(draft.recommendedChecks()),
                    draft.groundingStatus());
        }

        if (draft.groundingStatus() != GroundingStatus.GROUNDED || draft.findings().isEmpty()) {
            throw unavailable();
        }
        List<GroundedFinding> findings = draft.findings().stream()
                .map(finding -> resolveFinding(finding, sourceById))
                .toList();
        return new InvestigationAnswerResult(
                caseId,
                draft.summary().strip(),
                findings,
                stripChecks(draft.recommendedChecks()),
                draft.groundingStatus());
    }

    private static GroundedFinding resolveFinding(
            GeneratedFindingDraft finding,
            Map<String, GroundingSource> sourceById) {
        if (finding == null || !hasText(finding.text()) || finding.citationIds() == null
                || finding.citationIds().isEmpty()
                || finding.citationIds().stream().anyMatch(id -> !hasText(id))) {
            throw unavailable();
        }
        List<GroundingCitation> citations = finding.citationIds().stream()
                .distinct()
                .map(sourceById::get)
                .map(source -> {
                    if (source == null) {
                        throw unavailable();
                    }
                    return new GroundingCitation(
                            source.sourceId(), source.sourceType(), source.caseId(), source.text());
                })
                .toList();
        return new GroundedFinding(finding.text().strip(), citations);
    }

    private static List<String> stripChecks(List<String> checks) {
        return checks.stream().map(String::strip).toList();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static AnswerGenerationUnavailableException unavailable() {
        return new AnswerGenerationUnavailableException();
    }
}
