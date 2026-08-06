package io.github.monssifechadli99.transactiq.investigation_assistant.application;

import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EmbeddingPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EvidenceRetrievalPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceHit;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceSourceType;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.FocalEvidenceNotFoundException;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.InvestigationRetrievalResult;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.RelatedCaseGroup;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.RetrievedSource;
import io.github.monssifechadli99.transactiq.observability.PortfolioMetrics;
import io.github.monssifechadli99.transactiq.observability.PortfolioMetrics.Signal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates read-only investigation retrieval: load focal evidence, build a bounded
 * retrieval text, embed it, run hybrid BM25 + k-NN + RRF search excluding the focal case,
 * then group hits by related case so no single case can consume every result slot.
 */
public final class InvestigationRetrievalService {

    private final EvidenceRetrievalPort retrievalPort;
    private final EmbeddingPort embeddingPort;
    private final int candidatePoolSize;
    private final int focalTextMaxLength;
    private final int excerptMaxLength;
    private final PortfolioMetrics metrics;

    public InvestigationRetrievalService(
            EvidenceRetrievalPort retrievalPort,
            EmbeddingPort embeddingPort,
            int candidatePoolSize,
            int focalTextMaxLength,
            int excerptMaxLength) {
        this(
                retrievalPort,
                embeddingPort,
                candidatePoolSize,
                focalTextMaxLength,
                excerptMaxLength,
                PortfolioMetrics.noop());
    }

    public InvestigationRetrievalService(
            EvidenceRetrievalPort retrievalPort,
            EmbeddingPort embeddingPort,
            int candidatePoolSize,
            int focalTextMaxLength,
            int excerptMaxLength,
            PortfolioMetrics metrics) {
        this.retrievalPort = retrievalPort;
        this.embeddingPort = embeddingPort;
        this.candidatePoolSize = candidatePoolSize;
        this.focalTextMaxLength = focalTextMaxLength;
        this.excerptMaxLength = excerptMaxLength;
        this.metrics = metrics;
    }

    public InvestigationRetrievalResult retrieve(String caseId, String question, int maxRelatedCases) {
        try {
            InvestigationRetrievalResult result = doRetrieve(caseId, question, maxRelatedCases);
            metrics.increment(Signal.INVESTIGATION_RETRIEVED);
            return result;
        } catch (FocalEvidenceNotFoundException failure) {
            metrics.increment(Signal.INVESTIGATION_RETRIEVAL_MISSING);
            throw failure;
        } catch (RuntimeException failure) {
            metrics.increment(Signal.INVESTIGATION_RETRIEVAL_UNAVAILABLE);
            throw failure;
        }
    }

    private InvestigationRetrievalResult doRetrieve(String caseId, String question, int maxRelatedCases) {
        List<EvidenceHit> focalHits = retrievalPort.loadFocal(caseId);
        if (focalHits.isEmpty()) {
            throw new FocalEvidenceNotFoundException(caseId);
        }
        String retrievalText = buildRetrievalText(question, focalHits);
        float[] embedding = embeddingPort.embed(retrievalText);
        List<EvidenceHit> candidates =
                retrievalPort.hybridSearch(caseId, retrievalText, embedding, candidatePoolSize);
        return new InvestigationRetrievalResult(caseId, toSources(focalHits), group(candidates, maxRelatedCases));
    }

    private String buildRetrievalText(String question, List<EvidenceHit> focalHits) {
        String focalEvidence = focalHits.stream()
                .filter(hit -> hit.sourceType() == EvidenceSourceType.CASE_EVIDENCE)
                .map(EvidenceHit::text)
                .findFirst()
                .orElse("");
        return question + "\n\n" + bound(focalEvidence, focalTextMaxLength);
    }

    private List<RelatedCaseGroup> group(List<EvidenceHit> candidates, int maxRelatedCases) {
        Map<String, List<RetrievedSource>> byCase = new LinkedHashMap<>();
        for (EvidenceHit hit : candidates) {
            byCase.computeIfAbsent(hit.caseId(), key -> new ArrayList<>()).add(toSource(hit));
        }
        List<RelatedCaseGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<RetrievedSource>> entry : byCase.entrySet()) {
            if (groups.size() >= maxRelatedCases) {
                break;
            }
            groups.add(new RelatedCaseGroup(entry.getKey(), entry.getValue()));
        }
        return groups;
    }

    private List<RetrievedSource> toSources(List<EvidenceHit> hits) {
        return hits.stream().map(this::toSource).toList();
    }

    private RetrievedSource toSource(EvidenceHit hit) {
        return new RetrievedSource(
                hit.sourceId(), hit.sourceType(), hit.caseId(), bound(hit.text(), excerptMaxLength));
    }

    private static String bound(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
