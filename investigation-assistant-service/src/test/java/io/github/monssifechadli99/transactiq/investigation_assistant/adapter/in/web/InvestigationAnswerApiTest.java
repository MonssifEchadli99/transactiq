package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.InvestigationAnswerService;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.InvestigationRetrievalService;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.ChatGenerationPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EvidenceRetrievalPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceHit;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceSourceType;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GeneratedAnswerDraft;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GeneratedFindingDraft;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GroundingStatus;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class InvestigationAnswerApiTest {

    private static final String RESOLVER_LOGGER =
            "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver";
    private static final String FOCAL_SOURCE = "case:focal:evidence";

    @Test
    void servesTheFullRestRetrievalGenerationValidationAndMappingPath() throws Exception {
        AtomicInteger focalReads = new AtomicInteger();
        AtomicInteger hybridReads = new AtomicInteger();
        MockMvc mvc = mockMvc(focalReads, hybridReads, request -> new GeneratedAnswerDraft(
                "The synthetic velocity evidence warrants review.",
                List.of(new GeneratedFindingDraft(
                        "The focal evidence records a velocity pattern.", List.of(FOCAL_SOURCE))),
                List.of("Review the synthetic transaction timeline."),
                GroundingStatus.GROUNDED));
        String caseId = UUID.randomUUID().toString();

        String response = mvc.perform(post("/api/v1/fraud-cases/{caseId}/investigation/answer", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Why is this case suspicious?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(caseId))
                .andExpect(jsonPath("$.groundingStatus").value("GROUNDED"))
                .andExpect(jsonPath("$.findings[0].citations[0].sourceId").value(FOCAL_SOURCE))
                .andExpect(jsonPath("$.findings[0].citations[0].sourceType").value("CASE_EVIDENCE"))
                .andReturn().getResponse().getContentAsString();

        assertTrue(response.contains("focal excerpt"));
        assertFalse(response.contains("vector"));
        assertFalse(response.contains("integrity"));
        assertFalse(response.contains("publicationComplete"));
        assertTrue(focalReads.get() == 1 && hybridReads.get() == 1,
                "the answer path is read-only and retrieves evidence once");
    }

    @Test
    void returnsInsufficientEvidenceWithoutFindings() throws Exception {
        MockMvc mvc = mockMvc(new AtomicInteger(), new AtomicInteger(), request -> new GeneratedAnswerDraft(
                "There is not enough retrieved evidence to answer.",
                List.of(),
                List.of("Collect another synthetic evidence snapshot."),
                GroundingStatus.INSUFFICIENT_EVIDENCE));

        mvc.perform(post("/api/v1/fraud-cases/{caseId}/investigation/answer", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"What happened?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groundingStatus").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.findings").isEmpty());
    }

    @Test
    void malformedAndBlankQuestionsKeepTheSafe400Contract(CapturedOutput output) throws Exception {
        String sentinel = "ANSWER_MALFORMED_QUESTION_SENTINEL";
        MockMvc mvc = mockMvc(new AtomicInteger(), new AtomicInteger(), request -> {
            throw new AssertionError("Invalid questions must not reach generation");
        });

        withResolverDebug(() -> {
            mvc.perform(post("/api/v1/fraud-cases/{caseId}/investigation/answer", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"   \"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INVESTIGATION_REQUEST"));
            mvc.perform(post("/api/v1/fraud-cases/{caseId}/investigation/answer", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"" + sentinel + "\","))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INVESTIGATION_REQUEST"));
        });

        assertFalse(output.getAll().contains(sentinel), output.getAll());
    }

    @Test
    void providerAndInvalidGenerationFailuresAreSanitizedAndNotLogged(CapturedOutput output) throws Exception {
        String sentinel = "RAW_PROVIDER_BODY_SENTINEL";
        MockMvc providerFailure = mockMvc(new AtomicInteger(), new AtomicInteger(), request -> {
            throw new IllegalStateException(sentinel);
        });
        MockMvc invalidGeneration = mockMvc(new AtomicInteger(), new AtomicInteger(), request ->
                new GeneratedAnswerDraft(
                        "Unsupported output.",
                        List.of(new GeneratedFindingDraft("Invented fact.", List.of("unknown-source"))),
                        List.of("Check the source."),
                        GroundingStatus.GROUNDED));

        withResolverDebug(() -> {
            providerFailure.perform(post(
                            "/api/v1/fraud-cases/{caseId}/investigation/answer", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"Why?\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("INVESTIGATION_ANSWER_UNAVAILABLE"));
            invalidGeneration.perform(post(
                            "/api/v1/fraud-cases/{caseId}/investigation/answer", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"Why?\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("INVESTIGATION_ANSWER_UNAVAILABLE"));
        });

        assertTrue(output.getAll().contains("Investigation answer generation is unavailable"));
        assertFalse(output.getAll().contains(sentinel), output.getAll());
    }

    @Test
    void requestAndResponseToStringsRedactAnalystAndGeneratedContent() {
        InvestigationAnswerRequest request = new InvestigationAnswerRequest("SECRET_QUESTION");
        InvestigationAnswerResponse.Citation citation = new InvestigationAnswerResponse.Citation(
                "source", "CASE_EVIDENCE", "case", "SECRET_EVIDENCE");
        InvestigationAnswerResponse.Finding finding =
                new InvestigationAnswerResponse.Finding("SECRET_FINDING", List.of(citation));
        InvestigationAnswerResponse.Response response = new InvestigationAnswerResponse.Response(
                "case", "SECRET_SUMMARY", List.of(finding), List.of("SECRET_CHECK"), "GROUNDED");

        assertFalse(request.toString().contains("SECRET_QUESTION"));
        assertFalse(citation.toString().contains("SECRET_EVIDENCE"));
        assertFalse(finding.toString().contains("SECRET_FINDING"));
        assertFalse(response.toString().contains("SECRET_SUMMARY"));
        assertFalse(response.toString().contains("SECRET_CHECK"));
    }

    private static MockMvc mockMvc(
            AtomicInteger focalReads,
            AtomicInteger hybridReads,
            ChatGenerationPort generation) {
        EvidenceRetrievalPort retrieval = new EvidenceRetrievalPort() {
            @Override
            public List<EvidenceHit> loadFocal(String caseId) {
                focalReads.incrementAndGet();
                return List.of(new EvidenceHit(
                        FOCAL_SOURCE, EvidenceSourceType.CASE_EVIDENCE, caseId, "focal excerpt"));
            }

            @Override
            public List<EvidenceHit> hybridSearch(
                    String excludeCaseId,
                    String retrievalText,
                    float[] retrievalEmbedding,
                    int candidatePoolSize) {
                hybridReads.incrementAndGet();
                return List.of(new EvidenceHit(
                        "case:related:evidence",
                        EvidenceSourceType.CASE_EVIDENCE,
                        UUID.randomUUID().toString(),
                        "related excerpt"));
            }
        };
        InvestigationRetrievalService retrievalService =
                new InvestigationRetrievalService(retrieval, ignored -> new float[] {1.0f}, 10, 2000, 500);
        InvestigationAnswerService answerService = new InvestigationAnswerService(retrievalService, generation);
        ObjectMapper objectMapper = JsonMapper.builder().build();
        return MockMvcBuilders.standaloneSetup(new InvestigationAnswerController(
                        answerService, new InvestigationAnswerApiMapper(), objectMapper))
                .setControllerAdvice(new InvestigationExceptionHandler())
                .build();
    }

    private static void withResolverDebug(ThrowingAction action) throws Exception {
        Logger resolverLogger = (Logger) LoggerFactory.getLogger(RESOLVER_LOGGER);
        Level previousLevel = resolverLogger.getLevel();
        resolverLogger.setLevel(Level.DEBUG);
        try {
            action.run();
        } finally {
            resolverLogger.setLevel(previousLevel);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
