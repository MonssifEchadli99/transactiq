package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.mcp.McpInvestigationResponse.Answer;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.mcp.McpInvestigationResponse.Citation;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.mcp.McpInvestigationResponse.Finding;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.mcp.McpInvestigationResponse.RelatedCase;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.mcp.McpInvestigationResponse.Retrieval;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.mcp.McpInvestigationResponse.Source;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.InvestigationAnswerService;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.InvestigationRetrievalService;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.AnswerGenerationUnavailableException;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.ChatGenerationPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EmbeddingPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EvidenceIndexPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EvidenceRetrievalPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EvidenceStoreUnavailableException;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceDraft;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceHit;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceSourceType;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GeneratedAnswerDraft;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GeneratedFindingDraft;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GroundingStatus;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        classes = FraudInvestigationMcpProtocolTest.TestApplication.class,
        properties = {
            "spring.main.banner-mode=off",
            "spring.ai.model.chat=none",
            "spring.ai.model.embedding=none",
            "spring.ai.model.image=none",
            "spring.ai.model.moderation=none",
            "spring.ai.model.audio.speech=none",
            "spring.ai.model.audio.transcription=none",
            "spring.ai.mcp.server.enabled=true",
            "spring.ai.mcp.server.name=transactiq-investigation-assistant-test",
            "spring.ai.mcp.server.version=6C-test",
            "spring.ai.mcp.server.type=SYNC",
            "spring.ai.mcp.server.protocol=STREAMABLE",
            "spring.ai.mcp.server.annotation-scanner.enabled=true",
            "spring.ai.mcp.server.capabilities.tool=true",
            "spring.ai.mcp.server.capabilities.resource=false",
            "spring.ai.mcp.server.capabilities.prompt=false",
            "spring.ai.mcp.server.capabilities.completion=false",
            "spring.ai.mcp.server.streamable-http.mcp-endpoint=/mcp",
            "logging.level.io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.mcp=DEBUG",
            "logging.level.org.springframework.ai.mcp=OFF",
            "logging.level.io.modelcontextprotocol=OFF"
        })
class FraudInvestigationMcpProtocolTest {

    private static final String FOCAL_CASE = "00000000-0000-0000-0000-000000000601";
    private static final String RELATED_CASE = "00000000-0000-0000-0000-000000000602";
    private static final String MISSING_CASE = "00000000-0000-0000-0000-000000000603";
    private static final String UNAVAILABLE_CASE = "00000000-0000-0000-0000-000000000604";
    private static final String FOCAL_SOURCE = "case:" + FOCAL_CASE + ":evidence";
    private static final String RELATED_SOURCE = "case:" + RELATED_CASE + ":evidence";
    private static final String EVIDENCE_SENTINEL = "MCP_DEBUG_EVIDENCE_SENTINEL";
    private static final String FAILURE_SENTINEL = "MCP_RAW_FAILURE_SENTINEL";
    private static final String PROVIDER_QUESTION = "Trigger deterministic provider failure";
    private static final String INSUFFICIENT_QUESTION = "Is there enough evidence?";

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    ReadOnlyEvidenceFake evidenceFake;

    @Autowired
    DeterministicGenerationFake generationFake;

    @Autowired
    MutationProbe mutationProbe;

    private McpProtocolSession mcp;

    @BeforeAll
    void connectThroughStreamableHttp() throws Exception {
        mcp = McpProtocolSession.connect("http://127.0.0.1:" + port + "/mcp", objectMapper);
    }

    @AfterAll
    void closeProtocolSession() {
        if (mcp != null) {
            mcp.close();
        }
    }

    @Test
    void discoversExactlyTheTwoReadOnlyInvestigationTools() throws Exception {
        JsonNode tools = mcp.listTools();
        Set<String> names = StreamSupport.stream(tools.spliterator(), false)
                .map(tool -> tool.path("name").asText())
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of(
                FraudInvestigationMcpTools.RETRIEVE_TOOL_NAME,
                FraudInvestigationMcpTools.ANSWER_TOOL_NAME), names);
        assertEquals(2, tools.size());
        tools.forEach(tool -> {
            assertTrue(tool.path("inputSchema").path("required").toString().contains("caseId"));
            assertTrue(tool.path("inputSchema").path("required").toString().contains("question"));
            assertTrue(tool.path("annotations").path("readOnlyHint").asBoolean());
            assertFalse(tool.path("annotations").path("destructiveHint").asBoolean(true));
            assertFalse(tool.path("annotations").path("openWorldHint").asBoolean(true));
        });
    }

    @Test
    void retrievesSafeFocalAndRelatedEvidenceThroughTheMcpProtocol() throws Exception {
        JsonNode result = mcp.callTool(
                FraudInvestigationMcpTools.RETRIEVE_TOOL_NAME,
                arguments(FOCAL_CASE, "Why does the synthetic velocity pattern matter?"));

        assertSuccess(result);
        JsonNode content = result.path("structuredContent");
        assertEquals(FOCAL_CASE, content.path("caseId").asText());
        assertEquals(FOCAL_SOURCE, content.path("focalSources").get(0).path("sourceId").asText());
        assertEquals("CASE_EVIDENCE", content.path("focalSources").get(0).path("sourceType").asText());
        assertEquals(RELATED_CASE, content.path("relatedCases").get(0).path("caseId").asText());
        assertEquals(RELATED_SOURCE,
                content.path("relatedCases").get(0).path("sources").get(0).path("sourceId").asText());

        String serialized = content.toString();
        assertFalse(serialized.contains("vector"), serialized);
        assertFalse(serialized.contains("embedding"), serialized);
        assertFalse(serialized.contains("integrity"), serialized);
        assertFalse(serialized.contains("publicationComplete"), serialized);
        assertFalse(serialized.contains("assignee"), serialized);
        assertFalse(serialized.contains("resolver"), serialized);
        assertTrue(evidenceFake.focalReads.get() > 0);
        assertTrue(evidenceFake.hybridReads.get() > 0);
    }

    @Test
    void answersWithAValidatedCitationThroughTheMcpProtocol() throws Exception {
        JsonNode result = mcp.callTool(
                FraudInvestigationMcpTools.ANSWER_TOOL_NAME,
                arguments(FOCAL_CASE, "What is supported by the retrieved evidence?"));

        assertSuccess(result);
        JsonNode content = result.path("structuredContent");
        assertEquals(FOCAL_CASE, content.path("caseId").asText());
        assertEquals("GROUNDED", content.path("groundingStatus").asText());
        assertFalse(content.path("findings").isEmpty());
        JsonNode citation = content.path("findings").get(0).path("citations").get(0);
        assertEquals(FOCAL_SOURCE, citation.path("sourceId").asText());
        assertEquals(FOCAL_CASE, citation.path("caseId").asText());
        assertEquals("CASE_EVIDENCE", citation.path("sourceType").asText());
        assertEquals(EVIDENCE_SENTINEL, citation.path("excerpt").asText());
        assertTrue(generationFake.calls.get() > 0);
    }

    @Test
    void returnsAnInsufficientEvidenceAnswerWithoutFindings() throws Exception {
        JsonNode result = mcp.callTool(
                FraudInvestigationMcpTools.ANSWER_TOOL_NAME,
                arguments(FOCAL_CASE, INSUFFICIENT_QUESTION));

        assertSuccess(result);
        JsonNode content = result.path("structuredContent");
        assertEquals("INSUFFICIENT_EVIDENCE", content.path("groundingStatus").asText());
        assertTrue(content.path("findings").isEmpty());
        assertFalse(content.path("recommendedChecks").isEmpty());
    }

    @Test
    void returnsStableSanitizedErrorsForInvalidMissingAndUnavailableEvidence() throws Exception {
        assertToolError(
                mcp.callTool(
                        FraudInvestigationMcpTools.RETRIEVE_TOOL_NAME,
                        arguments(FOCAL_CASE, "   ")),
                "INVALID_INVESTIGATION_REQUEST");
        assertToolError(
                mcp.callTool(
                        FraudInvestigationMcpTools.RETRIEVE_TOOL_NAME,
                        arguments(MISSING_CASE, "What evidence exists?")),
                "FOCAL_EVIDENCE_NOT_FOUND");
        JsonNode unavailable = mcp.callTool(
                FraudInvestigationMcpTools.RETRIEVE_TOOL_NAME,
                arguments(UNAVAILABLE_CASE, "What evidence exists?"));
        assertToolError(unavailable, "INVESTIGATION_RETRIEVAL_UNAVAILABLE");
        assertFalse(unavailable.toString().contains(FAILURE_SENTINEL), unavailable::toString);
    }

    @Test
    void returnsAStableSanitizedProviderError() throws Exception {
        JsonNode result = mcp.callTool(
                FraudInvestigationMcpTools.ANSWER_TOOL_NAME,
                arguments(FOCAL_CASE, PROVIDER_QUESTION));

        assertToolError(result, "INVESTIGATION_ANSWER_UNAVAILABLE");
        assertFalse(result.toString().contains(FAILURE_SENTINEL), result::toString);
        assertFalse(result.toString().contains(PROVIDER_QUESTION), result::toString);
    }

    @Test
    void debugLogsDoNotRetainQuestionsEvidenceOrRawFailures(CapturedOutput output) throws Exception {
        String question = "MCP_DEBUG_QUESTION_SENTINEL";
        mcp.callTool(FraudInvestigationMcpTools.ANSWER_TOOL_NAME, arguments(FOCAL_CASE, question));
        mcp.callTool(
                FraudInvestigationMcpTools.RETRIEVE_TOOL_NAME,
                arguments(UNAVAILABLE_CASE, question));

        String logs = output.getAll();
        assertFalse(logs.contains(question), logs);
        assertFalse(logs.contains(EVIDENCE_SENTINEL), logs);
        assertFalse(logs.contains(FAILURE_SENTINEL), logs);
    }

    @Test
    void toolDtoDiagnosticRepresentationsRedactQuestionsAndEvidence() {
        String secretCase = "MCP_SECRET_CASE";
        String secretQuestion = "MCP_SECRET_QUESTION";
        String secretEvidence = "MCP_SECRET_EVIDENCE";
        Source source = new Source("MCP_SECRET_SOURCE", "CASE_EVIDENCE", secretCase, secretEvidence);
        RelatedCase relatedCase = new RelatedCase(secretCase, List.of(source));
        Retrieval retrieval = new Retrieval(secretCase, List.of(source), List.of(relatedCase));
        Citation citation = new Citation("MCP_SECRET_CITATION", "CASE_EVIDENCE", secretCase, secretEvidence);
        Finding finding = new Finding("MCP_SECRET_FINDING", List.of(citation));
        Answer answer = new Answer(
                secretCase,
                "MCP_SECRET_SUMMARY",
                List.of(finding),
                List.of("MCP_SECRET_CHECK"),
                "GROUNDED");

        List<String> diagnosticValues = List.of(
                new McpInvestigationRequest(secretCase, secretQuestion).toString(),
                source.toString(),
                relatedCase.toString(),
                retrieval.toString(),
                citation.toString(),
                finding.toString(),
                answer.toString());
        diagnosticValues.forEach(value -> {
            assertFalse(value.contains(secretCase), value);
            assertFalse(value.contains(secretQuestion), value);
            assertFalse(value.contains(secretEvidence), value);
            assertFalse(value.contains("MCP_SECRET_"), value);
        });
    }

    @Test
    void protocolToolsNeverUseTheMutationPortOrOpenAiModels() throws Exception {
        mcp.callTool(
                FraudInvestigationMcpTools.RETRIEVE_TOOL_NAME,
                arguments(FOCAL_CASE, "Retrieve without changing the case."));
        mcp.callTool(
                FraudInvestigationMcpTools.ANSWER_TOOL_NAME,
                arguments(FOCAL_CASE, "Answer without changing the case."));

        assertEquals(0, mutationProbe.writes.get(), "read-only MCP calls must not publish evidence or mutate a case");
        assertTrue(applicationContext.getBeansOfType(ChatModel.class).isEmpty(),
                "the protocol test must not configure a provider chat model");
        assertTrue(applicationContext.getBeansOfType(EmbeddingModel.class).isEmpty(),
                "the protocol test must not configure a provider embedding model");
    }

    private static Map<String, Object> arguments(String caseId, String question) {
        return Map.of("caseId", caseId, "question", question);
    }

    private static void assertSuccess(JsonNode result) {
        assertFalse(result.path("isError").asBoolean(true), result::toString);
        assertFalse(result.path("structuredContent").isMissingNode(), result::toString);
    }

    private static void assertToolError(JsonNode result, String expectedCode) {
        assertTrue(result.path("isError").asBoolean(), result::toString);
        assertEquals(expectedCode, result.path("structuredContent").path("code").asText());
        assertEquals(expectedCode, result.path("content").get(0).path("text").asText());
        assertEquals(1, result.path("structuredContent").size(), result::toString);
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(FraudInvestigationMcpTools.class)
    static class TestApplication {

        @Bean
        ReadOnlyEvidenceFake evidenceRetrievalPort() {
            return new ReadOnlyEvidenceFake();
        }

        @Bean
        EmbeddingPort embeddingPort() {
            return ignored -> new float[] {1.0f, 0.0f, 0.0f};
        }

        @Bean
        DeterministicGenerationFake chatGenerationPort() {
            return new DeterministicGenerationFake();
        }

        @Bean
        InvestigationRetrievalService investigationRetrievalService(
                ReadOnlyEvidenceFake evidenceRetrievalPort,
                EmbeddingPort embeddingPort) {
            return new InvestigationRetrievalService(
                    evidenceRetrievalPort, embeddingPort, 10, 2000, 500);
        }

        @Bean
        InvestigationAnswerService investigationAnswerService(
                InvestigationRetrievalService retrievalService,
                DeterministicGenerationFake generationPort) {
            return new InvestigationAnswerService(retrievalService, generationPort);
        }

        @Bean
        MutationProbe mutationProbe() {
            return new MutationProbe();
        }

        @Bean
        EvidenceIndexPort mutationProbePort(MutationProbe probe) {
            return new EvidenceIndexPort() {
                @Override
                public OptionalLong currentVersion(String sourceId) {
                    return OptionalLong.empty();
                }

                @Override
                public void index(EvidenceDraft draft, float[] embedding) {
                    probe.writes.incrementAndGet();
                }
            };
        }
    }

    static final class ReadOnlyEvidenceFake implements EvidenceRetrievalPort {

        private final AtomicInteger focalReads = new AtomicInteger();
        private final AtomicInteger hybridReads = new AtomicInteger();

        @Override
        public List<EvidenceHit> loadFocal(String caseId) {
            focalReads.incrementAndGet();
            if (MISSING_CASE.equals(caseId)) {
                return List.of();
            }
            if (UNAVAILABLE_CASE.equals(caseId)) {
                throw new EvidenceStoreUnavailableException(FAILURE_SENTINEL);
            }
            return List.of(new EvidenceHit(
                    "case:" + caseId + ":evidence",
                    EvidenceSourceType.CASE_EVIDENCE,
                    caseId,
                    EVIDENCE_SENTINEL));
        }

        @Override
        public List<EvidenceHit> hybridSearch(
                String excludeCaseId,
                String retrievalText,
                float[] retrievalEmbedding,
                int candidatePoolSize) {
            hybridReads.incrementAndGet();
            return List.of(new EvidenceHit(
                    RELATED_SOURCE,
                    EvidenceSourceType.CASE_EVIDENCE,
                    RELATED_CASE,
                    "Synthetic related-case velocity evidence."));
        }
    }

    static final class DeterministicGenerationFake implements ChatGenerationPort {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public GeneratedAnswerDraft generate(
                io.github.monssifechadli99.transactiq.investigation_assistant.domain.ChatGenerationRequest request) {
            calls.incrementAndGet();
            if (PROVIDER_QUESTION.equals(request.question())) {
                throw new AnswerGenerationUnavailableException(FAILURE_SENTINEL);
            }
            if (INSUFFICIENT_QUESTION.equals(request.question())) {
                return new GeneratedAnswerDraft(
                        "There is not enough retrieved evidence to answer.",
                        List.of(),
                        List.of("Collect another synthetic evidence snapshot."),
                        GroundingStatus.INSUFFICIENT_EVIDENCE);
            }
            return new GeneratedAnswerDraft(
                    "The retrieved synthetic evidence supports further review.",
                    List.of(new GeneratedFindingDraft(
                            "The focal evidence records the synthetic velocity pattern.",
                            List.of(request.sources().getFirst().sourceId()))),
                    List.of("Review the synthetic transaction timeline."),
                    GroundingStatus.GROUNDED);
        }
    }

    static final class MutationProbe {
        private final AtomicInteger writes = new AtomicInteger();
    }

    /** Minimal real Streamable HTTP JSON-RPC client used only by this offline test. */
    static final class McpProtocolSession implements AutoCloseable {

        private static final String REQUESTED_PROTOCOL_VERSION = "2025-06-18";

        private final URI endpoint;
        private final ObjectMapper mapper;
        private final HttpClient client;
        private final AtomicInteger requestIds = new AtomicInteger(1);
        private String sessionId;
        private String protocolVersion;

        private McpProtocolSession(String endpoint, ObjectMapper mapper) {
            this.endpoint = URI.create(endpoint);
            this.mapper = mapper;
            this.client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
        }

        static McpProtocolSession connect(String endpoint, ObjectMapper mapper) throws Exception {
            McpProtocolSession session = new McpProtocolSession(endpoint, mapper);
            int id = session.requestIds.getAndIncrement();
            Map<String, Object> params = Map.of(
                    "protocolVersion", REQUESTED_PROTOCOL_VERSION,
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "transactiq-6c-test", "version", "1.0"));
            HttpResponse<String> http = session.send(session.requestEnvelope(id, "initialize", params), false);
            assertEquals(200, http.statusCode(), http.body());
            JsonNode response = session.parseResponse(http.body());
            assertFalse(response.has("error"), response::toString);
            assertEquals(id, response.path("id").asInt());
            session.sessionId = http.headers().firstValue("mcp-session-id").orElseThrow(
                    () -> new AssertionError("MCP initialize response did not establish a session"));
            session.protocolVersion = response.path("result").path("protocolVersion").asText();
            assertFalse(session.protocolVersion.isBlank(), response::toString);

            Map<String, Object> initialized = new LinkedHashMap<>();
            initialized.put("jsonrpc", "2.0");
            initialized.put("method", "notifications/initialized");
            initialized.put("params", Map.of());
            HttpResponse<String> notification = session.send(
                    mapper.writeValueAsString(initialized), true);
            assertTrue(notification.statusCode() == 200 || notification.statusCode() == 202,
                    () -> "Unexpected initialized status " + notification.statusCode());
            return session;
        }

        JsonNode listTools() throws Exception {
            return request("tools/list", Map.of()).path("tools");
        }

        JsonNode callTool(String name, Map<String, Object> arguments) throws Exception {
            return request("tools/call", Map.of("name", name, "arguments", arguments));
        }

        private JsonNode request(String method, Map<String, Object> params) throws Exception {
            int id = requestIds.getAndIncrement();
            HttpResponse<String> http = send(requestEnvelope(id, method, params), true);
            assertEquals(200, http.statusCode(), http.body());
            JsonNode response = parseResponse(http.body());
            assertEquals(id, response.path("id").asInt(), response::toString);
            assertFalse(response.has("error"), response::toString);
            JsonNode result = response.path("result");
            assertFalse(result.isMissingNode(), response::toString);
            return result;
        }

        private String requestEnvelope(int id, String method, Map<String, Object> params) throws Exception {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", method);
            request.put("params", params);
            return mapper.writeValueAsString(request);
        }

        private HttpResponse<String> send(String body, boolean includeSession) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream");
            if (includeSession) {
                assertNotNull(sessionId);
                request.header("Mcp-Session-Id", sessionId);
                request.header("MCP-Protocol-Version", protocolVersion);
            }
            return client.send(
                    request.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        private JsonNode parseResponse(String body) throws Exception {
            String stripped = body.strip();
            if (stripped.startsWith("{")) {
                return mapper.readTree(stripped);
            }
            for (String line : body.lines().toList()) {
                if (line.startsWith("data:")) {
                    String data = line.substring("data:".length()).strip();
                    if (!data.isBlank()) {
                        return mapper.readTree(data);
                    }
                }
            }
            throw new AssertionError("MCP response did not contain a JSON-RPC message");
        }

        @Override
        public void close() {
            if (sessionId == null) {
                return;
            }
            try {
                HttpRequest request = HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(5))
                        .header("Mcp-Session-Id", sessionId)
                        .header("MCP-Protocol-Version", protocolVersion)
                        .DELETE()
                        .build();
                client.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
                // The Spring context also owns and closes all remaining protocol sessions.
            }
        }
    }
}
