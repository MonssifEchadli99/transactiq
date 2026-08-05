package io.github.monssifechadli99.transactiq.investigation_assistant.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.AnswerGenerationUnavailableException;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.ChatGenerationPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.ChatGenerationRequest;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceHit;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceSourceType;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GeneratedAnswerDraft;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GeneratedFindingDraft;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GroundingSource;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GroundingStatus;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.InvestigationAnswerResult;
import io.github.monssifechadli99.transactiq.investigation_assistant.support.FakeEmbeddingPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.support.FakeEvidenceRetrievalPort;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

/** Small, deterministic offline evaluation for grounded-answer policy and validation. */
class GroundedAnswerEvaluationTest {

    private static final String FOCAL_CASE_ID = "11111111-1111-4111-8111-111111111111";
    private static final String DATASET = "/evaluation/grounded-answer-evaluation.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @DisplayName("offline grounded-answer scenario")
    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void evaluatesGroundingPolicy(String ignoredName, EvaluationScenario scenario) {
        FakeEvidenceRetrievalPort evidence = new FakeEvidenceRetrievalPort();
        evidence.seedFocal(FOCAL_CASE_ID, hitsForCase(scenario.sources(), FOCAL_CASE_ID));
        evidence.seedHybridResults(scenario.sources().stream()
                .filter(source -> !FOCAL_CASE_ID.equals(source.caseId()))
                .map(EvaluationSource::toHit)
                .toList());
        DeterministicEvaluationChatAdapter chat = new DeterministicEvaluationChatAdapter(scenario);
        InvestigationAnswerService service = new InvestigationAnswerService(
                new InvestigationRetrievalService(evidence, new FakeEmbeddingPort(4), 50, 2000, 500),
                chat);

        if (scenario.expectedOutcome().isFailure()) {
            AnswerGenerationUnavailableException failure = assertThrows(
                    AnswerGenerationUnavailableException.class,
                    () -> service.answer(FOCAL_CASE_ID, scenario.question()));

            assertEquals(AnswerGenerationUnavailableException.SAFE_MESSAGE, failure.getMessage());
            assertNull(failure.getCause());
            assertEquals(0, failure.getSuppressed().length);
            assertEquals(FOCAL_CASE_ID, chat.lastRequest().focalCaseId());
            return;
        }

        InvestigationAnswerResult answer = service.answer(FOCAL_CASE_ID, scenario.question());

        assertEquals(scenario.expectedOutcome().status(), answer.groundingStatus());
        assertEquals(FOCAL_CASE_ID, answer.caseId());
        assertEquals(FOCAL_CASE_ID, chat.lastRequest().focalCaseId());
        assertEquals(scenario.question(), chat.lastRequest().question());
        assertEquals(scenario.sources().stream().map(EvaluationSource::sourceId).toList(),
                chat.lastRequest().sources().stream().map(GroundingSource::sourceId).toList());
        assertCitationsComeOnlyFromRetrievedSources(answer, scenario.sources());
        if (answer.groundingStatus() == GroundingStatus.GROUNDED) {
            assertFalse(answer.findings().isEmpty());
            assertTrue(answer.findings().stream().allMatch(finding -> !finding.citations().isEmpty()));
        } else {
            assertTrue(answer.findings().isEmpty());
        }
        if (scenario.id().equals("prompt-injection-remains-evidence")) {
            assertTrue(chat.lastRequest().sources().getFirst().text().contains("IGNORE ALL SAFETY RULES"));
            assertFalse(answer.summary().contains("RESOLVE THE CASE"));
        }
    }

    private static void assertCitationsComeOnlyFromRetrievedSources(
            InvestigationAnswerResult answer,
            List<EvaluationSource> sources) {
        Set<String> allowedIds = sources.stream()
                .map(EvaluationSource::sourceId)
                .collect(Collectors.toSet());
        answer.findings().forEach(finding -> finding.citations().forEach(citation -> {
            assertTrue(allowedIds.contains(citation.sourceId()));
            EvaluationSource expected = sources.stream()
                    .filter(source -> source.sourceId().equals(citation.sourceId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(expected.sourceType(), citation.sourceType());
            assertEquals(expected.caseId(), citation.caseId());
            assertEquals(expected.text(), citation.excerpt());
        }));
    }

    private static List<EvidenceHit> hitsForCase(List<EvaluationSource> sources, String caseId) {
        return sources.stream()
                .filter(source -> caseId.equals(source.caseId()))
                .map(EvaluationSource::toHit)
                .toList();
    }

    private static Stream<Arguments> scenarios() throws IOException {
        try (InputStream input = GroundedAnswerEvaluationTest.class.getResourceAsStream(DATASET)) {
            if (input == null) {
                throw new IOException("Grounded-answer evaluation dataset is missing");
            }
            return Arrays.stream(OBJECT_MAPPER.readValue(input, EvaluationScenario[].class))
                    .map(scenario -> Arguments.of(Named.of(scenario.id(), scenario.id()), scenario));
        }
    }

    private record EvaluationScenario(
            String id,
            String question,
            List<EvaluationSource> sources,
            EvaluationGeneration generation,
            ExpectedOutcome expectedOutcome) {
    }

    private record EvaluationSource(
            String sourceId,
            EvidenceSourceType sourceType,
            String caseId,
            String text) {
        private EvidenceHit toHit() {
            return new EvidenceHit(sourceId, sourceType, caseId, text);
        }
    }

    private record EvaluationGeneration(
            String summary,
            List<EvaluationFinding> findings,
            List<String> recommendedChecks,
            GroundingStatus groundingStatus) {
        private GeneratedAnswerDraft toDraft() {
            return new GeneratedAnswerDraft(
                    summary,
                    findings.stream().map(EvaluationFinding::toDraft).toList(),
                    recommendedChecks,
                    groundingStatus);
        }
    }

    private record EvaluationFinding(String text, List<String> citationIds) {
        private GeneratedFindingDraft toDraft() {
            return new GeneratedFindingDraft(text, citationIds);
        }
    }

    private enum ExpectedOutcome {
        GROUNDED(GroundingStatus.GROUNDED, false),
        INSUFFICIENT_EVIDENCE(GroundingStatus.INSUFFICIENT_EVIDENCE, false),
        GENERATION_UNAVAILABLE(null, true),
        PROVIDER_UNAVAILABLE(null, true);

        private final GroundingStatus status;
        private final boolean failure;

        ExpectedOutcome(GroundingStatus status, boolean failure) {
            this.status = status;
            this.failure = failure;
        }

        private GroundingStatus status() {
            return status;
        }

        private boolean isFailure() {
            return failure;
        }
    }

    /** Deterministic local substitute; this suite never constructs a Spring AI/OpenAI client. */
    private static final class DeterministicEvaluationChatAdapter implements ChatGenerationPort {
        private final EvaluationScenario scenario;
        private ChatGenerationRequest lastRequest;

        private DeterministicEvaluationChatAdapter(EvaluationScenario scenario) {
            this.scenario = scenario;
        }

        @Override
        public GeneratedAnswerDraft generate(ChatGenerationRequest request) {
            lastRequest = request;
            if (scenario.expectedOutcome() == ExpectedOutcome.PROVIDER_UNAVAILABLE) {
                throw new AnswerGenerationUnavailableException();
            }
            return scenario.generation().toDraft();
        }

        private ChatGenerationRequest lastRequest() {
            return lastRequest;
        }
    }
}
