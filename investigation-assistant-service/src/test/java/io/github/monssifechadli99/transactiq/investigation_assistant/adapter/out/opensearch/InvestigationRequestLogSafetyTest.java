package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.out.opensearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web.InvestigationApiMapper;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web.InvestigationExceptionHandler;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web.InvestigationRetrievalController;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web.InvestigationRetrievalRequest;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web.InvestigationRetrievalResponse;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.InvestigationRetrievalService;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EmbeddingPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EvidenceStoreUnavailableException;
import io.github.monssifechadli99.transactiq.investigation_assistant.configuration.InvestigationOpenSearchProperties;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class InvestigationRequestLogSafetyTest {

    private static final String QUESTION_SENTINEL = "ANALYST_QUESTION_LOG_SENTINEL_6A";
    private static final String RULE_EVIDENCE_SENTINEL = "RULE_EVIDENCE_LOG_SENTINEL_6A";
    private static final String RATIONALE_SENTINEL = "RESOLUTION_RATIONALE_LOG_SENTINEL_6A";
    private static final String FOCAL_TEXT_SENTINEL = "FOCAL_RETRIEVAL_TEXT_LOG_SENTINEL_6A";
    private static final String PRIVATE_INTEGRITY_SENTINEL = "PRIVATE_INTEGRITY_LOG_SENTINEL_6A";
    private static final String MGET_ERROR_SENTINEL = "MGET_ITEM_ERROR_LOG_SENTINEL_6A";
    private static final float[] VECTOR_SENTINEL = {0.31415927f, -0.27182817f, 0.1618034f};

    @Test
    void productionWebAndOpenSearchPathsNeverLogSensitiveBodies(CapturedOutput output) throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        AtomicInteger requests = new AtomicInteger();
        AtomicBoolean returnEchoingFailure = new AtomicBoolean();
        List<String> receivedBodies = Collections.synchronizedList(new ArrayList<>());
        String focalCaseId = UUID.randomUUID().toString();
        String relatedCaseId = UUID.randomUUID().toString();
        String focalText = String.join(" ", FOCAL_TEXT_SENTINEL, RULE_EVIDENCE_SENTINEL, RATIONALE_SENTINEL);
        HttpServer server = startServer(exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            receivedBodies.add(requestBody);
            if (returnEchoingFailure.get()) {
                respond(exchange, 503, requestBody);
                return;
            }
            int requestNumber = requests.incrementAndGet();
            String response = switch (requestNumber) {
                case 1 -> completeSnapshotResponse(mapper, focalCaseId, focalText, true);
                case 2 -> searchResponse(mapper, List.of(activeHit(relatedCaseId, focalText)));
                case 3 -> completeSnapshotResponse(mapper, relatedCaseId, focalText, false);
                default -> throw new AssertionError("Unexpected OpenSearch request " + requestNumber);
            };
            respond(exchange, 200, response);
        });
        LogLevels logLevels = LogLevels.enableSensitivePathDiagnostics();
        try {
            assertEquals(
                    "LogSafeJsonBody[content=<redacted>]",
                    LogSafeJsonBody.of(mapper, Map.of("sensitive", QUESTION_SENTINEL)).toString());
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Duration.ofSeconds(2));
            requestFactory.setReadTimeout(Duration.ofSeconds(2));
            OpenSearchEvidenceRetrievalStore retrievalStore = new OpenSearchEvidenceRetrievalStore(
                    RestClient.builder()
                            .baseUrl(baseUrl(server))
                            .requestFactory(requestFactory)
                            .build(),
                    mapper,
                    properties(baseUrl(server)));
            EmbeddingPort embeddingPort = text -> VECTOR_SENTINEL.clone();
            InvestigationRetrievalService service =
                    new InvestigationRetrievalService(retrievalStore, embeddingPort, 3, 2000, 500);
            MockMvc mockMvc = MockMvcBuilders
                    .standaloneSetup(new InvestigationRetrievalController(service, new InvestigationApiMapper(), mapper))
                    .setControllerAdvice(new InvestigationExceptionHandler())
                    .build();

            String requestJson = mapper.writeValueAsString(Map.of(
                    "question", QUESTION_SENTINEL,
                    "maxRelatedCases", 2));
            String responseJson = mockMvc.perform(post(
                            "/api/v1/fraud-cases/{caseId}/investigation/retrieval", focalCaseId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertEquals(3, receivedBodies.size());
            String hybridBody = receivedBodies.get(1);
            assertTrue(hybridBody.contains(QUESTION_SENTINEL), "wire body must contain the requested search text");
            assertTrue(hybridBody.contains(FOCAL_TEXT_SENTINEL), "wire body must contain bounded focal evidence");
            assertTrue(hybridBody.contains(RULE_EVIDENCE_SENTINEL), "wire body must contain canonical rule evidence");
            assertTrue(hybridBody.contains(RATIONALE_SENTINEL), "wire body must contain canonical rationale evidence");
            String serializedVector = mapper.writeValueAsString(VECTOR_SENTINEL);
            assertTrue(hybridBody.contains(serializedVector), "wire body must contain the embedding vector");
            assertTrue(responseJson.contains(FOCAL_TEXT_SENTINEL), "the safe evidence excerpt remains in the API response");
            assertFalse(responseJson.contains(PRIVATE_INTEGRITY_SENTINEL), responseJson);

            assertRedactedDtoRepresentations(focalCaseId, focalText);

            returnEchoingFailure.set(true);
            EvidenceStoreUnavailableException failure = assertThrows(
                    EvidenceStoreUnavailableException.class,
                    () -> retrievalStore.hybridSearch(focalCaseId, hybridBody, VECTOR_SENTINEL, 3));
            assertEquals("Fraud investigation evidence search is unavailable", failure.getMessage());
            assertNoSentinels(stackTrace(failure));

            String logs = output.getAll();
            assertNoSentinels(logs);
            assertFalse(logs.contains(serializedVector), logs);
            assertFalse(logs.contains(hybridBody), logs);
        } finally {
            logLevels.restore();
            server.stop(0);
        }
    }

    @Test
    void focalMgetItemFailureIsSanitizedUnavailableInsteadOfMissingEvidence(CapturedOutput output)
            throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        String caseId = UUID.randomUUID().toString();
        HttpServer server = startServer(exchange -> respond(exchange, 200, mapper.writeValueAsString(Map.of(
                "docs", List.of(
                        Map.of(
                                "_id", "case:" + caseId + ":evidence",
                                "error", Map.of("reason", MGET_ERROR_SENTINEL)),
                        Map.of(
                                "_id", "case:" + caseId + ":resolution",
                                "found", false))))));
        LogLevels logLevels = LogLevels.enableSensitivePathDiagnostics();
        try {
            OpenSearchEvidenceRetrievalStore retrievalStore = new OpenSearchEvidenceRetrievalStore(
                    RestClient.builder().baseUrl(baseUrl(server)).build(),
                    mapper,
                    properties(baseUrl(server)));

            EvidenceStoreUnavailableException failure = assertThrows(
                    EvidenceStoreUnavailableException.class,
                    () -> retrievalStore.loadFocal(caseId));

            assertEquals("Fraud investigation evidence search is unavailable", failure.getMessage());
            assertFalse(stackTrace(failure).contains(MGET_ERROR_SENTINEL), stackTrace(failure));
            assertFalse(output.getAll().contains(MGET_ERROR_SENTINEL), output.getAll());
        } finally {
            logLevels.restore();
            server.stop(0);
        }
    }

    private static void assertRedactedDtoRepresentations(String caseId, String excerpt) {
        InvestigationRetrievalRequest request = new InvestigationRetrievalRequest(QUESTION_SENTINEL, 2);
        InvestigationRetrievalResponse.Source source =
                new InvestigationRetrievalResponse.Source("source", "CASE_EVIDENCE", caseId, excerpt);
        InvestigationRetrievalResponse.RelatedCase relatedCase =
                new InvestigationRetrievalResponse.RelatedCase(caseId, List.of(source));
        InvestigationRetrievalResponse.Response response =
                new InvestigationRetrievalResponse.Response(caseId, List.of(source), List.of(relatedCase));

        assertNoSentinels(request.toString());
        assertNoSentinels(source.toString());
        assertNoSentinels(relatedCase.toString());
        assertNoSentinels(response.toString());
    }

    private static HttpServer startServer(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static InvestigationOpenSearchProperties properties(String url) {
        return new InvestigationOpenSearchProperties(
                url, Duration.ofSeconds(2), "unused-index", "evidence-read", "unused-write", "hybrid-pipeline");
    }

    private static String completeSnapshotResponse(
            ObjectMapper mapper, String caseId, String text, boolean resolutionExpected) {
        Map<String, Object> evidence = activeSource(
                "case:" + caseId + ":evidence", "CASE_EVIDENCE", caseId, text);
        evidence.put("publicationComplete", true);
        evidence.put("resolutionExpected", resolutionExpected);
        Map<String, Object> resolution = resolutionExpected
                ? activeSource("case:" + caseId + ":resolution", "RESOLUTION", caseId, text)
                : absentSource("case:" + caseId + ":resolution");
        return mapper.writeValueAsString(Map.of("docs", List.of(
                foundDocument("case:" + caseId + ":evidence", evidence),
                foundDocument("case:" + caseId + ":resolution", resolution))));
    }

    private static Map<String, Object> activeHit(String caseId, String text) {
        return Map.of(
                "_score", 1.0,
                "_source", activeSource("case:" + caseId + ":evidence", "CASE_EVIDENCE", caseId, text));
    }

    private static Map<String, Object> activeSource(
            String sourceId, String sourceType, String caseId, String text) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("sourceId", sourceId);
        source.put("sourceType", sourceType);
        source.put("caseId", caseId);
        source.put("text", text);
        source.put("projectionVersion", 7L);
        source.put("projectionIntegrity", PRIVATE_INTEGRITY_SENTINEL);
        source.put("chunkState", "ACTIVE");
        return source;
    }

    private static Map<String, Object> absentSource(String sourceId) {
        return Map.of(
                "sourceId", sourceId,
                "projectionVersion", 7L,
                "projectionIntegrity", PRIVATE_INTEGRITY_SENTINEL,
                "chunkState", "ABSENT");
    }

    private static Map<String, Object> foundDocument(String sourceId, Map<String, Object> source) {
        return Map.of("_id", sourceId, "found", true, "_source", source);
    }

    private static String searchResponse(ObjectMapper mapper, List<Map<String, Object>> hits) {
        return mapper.writeValueAsString(Map.of("hits", Map.of("hits", hits)));
    }

    private static void respond(HttpExchange exchange, int status, String response) throws java.io.IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String stackTrace(Throwable error) {
        StringWriter text = new StringWriter();
        error.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    private static void assertNoSentinels(String text) {
        assertFalse(text.contains(QUESTION_SENTINEL), text);
        assertFalse(text.contains(RULE_EVIDENCE_SENTINEL), text);
        assertFalse(text.contains(RATIONALE_SENTINEL), text);
        assertFalse(text.contains(FOCAL_TEXT_SENTINEL), text);
        assertFalse(text.contains(PRIVATE_INTEGRITY_SENTINEL), text);
        assertFalse(text.contains(MGET_ERROR_SENTINEL), text);
    }

    private static final class LogLevels {
        private final Map<Logger, Level> previous;

        private LogLevels(Map<Logger, Level> previous) {
            this.previous = previous;
        }

        static LogLevels enableSensitivePathDiagnostics() {
            Map<Logger, Level> previous = new LinkedHashMap<>();
            set(previous, Logger.ROOT_LOGGER_NAME, Level.INFO);
            set(previous, "org.springframework.web.client.DefaultRestClient", Level.DEBUG);
            set(previous, "org.springframework.web.servlet.mvc.method.annotation", Level.DEBUG);
            set(previous, "org.springframework.http.converter", Level.TRACE);
            set(previous, "org.apache.hc.client5.http.headers", Level.TRACE);
            set(previous, "org.apache.hc.client5.http.wire", Level.TRACE);
            set(previous, "org.apache.http.headers", Level.TRACE);
            set(previous, "org.apache.http.wire", Level.TRACE);
            set(previous, "reactor.netty.http.client", Level.TRACE);
            return new LogLevels(previous);
        }

        private static void set(Map<Logger, Level> previous, String name, Level level) {
            Logger logger = (Logger) LoggerFactory.getLogger(name);
            previous.put(logger, logger.getLevel());
            logger.setLevel(level);
        }

        void restore() {
            previous.forEach(Logger::setLevel);
        }
    }
}
