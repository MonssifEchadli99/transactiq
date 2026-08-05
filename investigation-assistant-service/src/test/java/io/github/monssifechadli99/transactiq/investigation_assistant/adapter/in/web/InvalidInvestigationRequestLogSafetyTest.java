package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.InvestigationRetrievalService;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EvidenceRetrievalPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceHit;
import java.util.List;
import java.util.UUID;
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
class InvalidInvestigationRequestLogSafetyTest {

    private static final String RESOLVER_LOGGER =
            "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver";
    private static final String INVALID_CODE = "INVALID_INVESTIGATION_REQUEST";

    @Test
    void blankQuestionKeepsTheExistingValidationContractWithoutLoggingTheRejectedBody(CapturedOutput output)
            throws Exception {
        String requestBody = "{\"question\":\"   \"}";
        String expectedResponse = "{\"code\":\"" + INVALID_CODE
                + "\",\"fieldErrors\":[{\"field\":\"question\",\"message\":\"must not be blank\"},"
                + "{\"field\":\"question\",\"message\":\"size must be between 1 and 1000\"}]}";

        assertSafeBadRequest(requestBody, expectedResponse, requestBody, output);
    }

    @Test
    void oversizedQuestionDoesNotReachFieldErrorsResponsesOrResolverDebugLogs(CapturedOutput output)
            throws Exception {
        String sentinel = "OVERSIZED_QUESTION_LOG_SENTINEL_6A";
        ObjectMapper objectMapper = JsonMapper.builder().build();
        String requestBody = objectMapper.writeValueAsString(new InvestigationRetrievalRequest(
                "x".repeat(1001) + sentinel, 5));
        String expectedResponse = "{\"code\":\"" + INVALID_CODE
                + "\",\"fieldErrors\":[{\"field\":\"question\","
                + "\"message\":\"size must be between 1 and 1000\"}]}";

        assertSafeBadRequest(requestBody, expectedResponse, sentinel, output);
    }

    @Test
    void malformedJsonDoesNotExposeParserInputInResponsesOrResolverDebugLogs(CapturedOutput output)
            throws Exception {
        String sentinel = "MALFORMED_JSON_LOG_SENTINEL_6A";
        String requestBody = "{\"question\":\"" + sentinel + "\",";
        String expectedResponse = "{\"code\":\"" + INVALID_CODE + "\"}";

        assertSafeBadRequest(requestBody, expectedResponse, sentinel, output);
    }

    private static void assertSafeBadRequest(
            String requestBody,
            String expectedResponse,
            String prohibitedText,
            CapturedOutput output) throws Exception {
        Logger resolverLogger = (Logger) LoggerFactory.getLogger(RESOLVER_LOGGER);
        Level previousLevel = resolverLogger.getLevel();
        resolverLogger.setLevel(Level.DEBUG);
        try {
            String response = mockMvc().perform(post(
                            "/api/v1/fraud-cases/{caseId}/investigation/retrieval", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertEquals(expectedResponse, response);
            assertFalse(response.contains(prohibitedText), response);

            String logs = output.getAll();
            assertTrue(logs.contains("Resolved ["), logs);
            assertTrue(logs.contains("InvalidInvestigationRequestException: Invalid investigation request"), logs);
            assertFalse(logs.contains(prohibitedText), logs);
        } finally {
            resolverLogger.setLevel(previousLevel);
        }
    }

    private static MockMvc mockMvc() {
        EvidenceRetrievalPort unreachableRetrieval = new EvidenceRetrievalPort() {
            @Override
            public List<EvidenceHit> loadFocal(String caseId) {
                throw new AssertionError("Invalid requests must not reach evidence retrieval");
            }

            @Override
            public List<EvidenceHit> hybridSearch(
                    String excludeCaseId,
                    String retrievalText,
                    float[] retrievalEmbedding,
                    int candidatePoolSize) {
                throw new AssertionError("Invalid requests must not reach evidence retrieval");
            }
        };
        InvestigationRetrievalService service =
                new InvestigationRetrievalService(unreachableRetrieval, text -> {
                    throw new AssertionError("Invalid requests must not reach embedding");
                }, 3, 2000, 500);
        ObjectMapper objectMapper = JsonMapper.builder().build();
        return MockMvcBuilders
                .standaloneSetup(new InvestigationRetrievalController(
                        service, new InvestigationApiMapper(), objectMapper))
                .setControllerAdvice(new InvestigationExceptionHandler())
                .build();
    }
}
