package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.kafka;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudCaseProjectionEvent;
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudCaseProjectionSnapshot;
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudRuleEvidence;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.ProjectionValidator;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.SafeEvidenceMapper;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EmbeddingPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EvidenceIndexPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EvidenceRetrievalPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EvidenceStoreUnavailableException;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.out.opensearch.OpenSearchEvidenceIndexStore;
import io.github.monssifechadli99.transactiq.investigation_assistant.configuration.InvestigationOpenSearchProperties;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceDraft;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceHit;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.ValidatedProjection;
import io.github.monssifechadli99.transactiq.investigation_assistant.support.DeterministicEmbeddingAdapter;
import io.github.monssifechadli99.transactiq.investigation_assistant.support.ProjectionFixtures;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@TestMethodOrder(OrderAnnotation.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "OPENAI_API_KEY=test-only-never-sent-placeholder",
        "investigation-assistant.consumer.topic=investigation-projection-kafka-it",
        "investigation-assistant.consumer.group-id=investigation-projection-kafka-it-group",
        "investigation-assistant.consumer.dlt-topic=investigation-projection-kafka-it.dlt",
        "investigation-assistant.consumer.retry-interval=50ms",
        "investigation-assistant.consumer.retry-attempts=5",
        "investigation-assistant.opensearch.physical-index=investigation-kafka-it-v1",
        "investigation-assistant.opensearch.read-alias=investigation-kafka-it-read",
        "investigation-assistant.opensearch.write-alias=investigation-kafka-it-write",
        "investigation-assistant.opensearch.request-timeout=5s",
        "logging.level.org.springframework.web=DEBUG",
        "logging.level.org.springframework.web.client=DEBUG",
        "logging.level.org.springframework.http.converter=TRACE",
        "logging.level.org.springframework.ai=TRACE",
        "logging.level.org.apache.hc.client5.http.wire=TRACE",
        "logging.level.org.apache.http.wire=TRACE",
        "logging.level.reactor.netty.http.client=TRACE"
})
@Import(InvestigationProjectionKafkaIntegrationTest.TestAdapters.class)
class InvestigationProjectionKafkaIntegrationTest {

    private static final String TOPIC = "investigation-projection-kafka-it";
    private static final String DLT_TOPIC = TOPIC + ".dlt";
    private static final String GROUP_ID = "investigation-projection-kafka-it-group";

    @Container
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka-native:4.1.0"));

    @Container
    static final GenericContainer<?> openSearch =
            new GenericContainer<>(DockerImageName.parse("opensearchproject/opensearch:3.2.0"))
                    .withEnv("discovery.type", "single-node")
                    .withEnv("DISABLE_SECURITY_PLUGIN", "true")
                    .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
                    .withExposedPorts(9200);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("investigation-assistant.opensearch.url", InvestigationProjectionKafkaIntegrationTest::openSearchUrl);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAdapters {
        @Bean
        @Primary
        RecordingEmbeddingPort recordingEmbeddingPort() {
            return new RecordingEmbeddingPort(1536);
        }

        @Bean
        @Primary
        ControlledEvidenceIndexPort controlledEvidenceIndexPort(
                @Qualifier("evidenceIndexPort") EvidenceIndexPort delegate,
                ObjectMapper objectMapper,
                InvestigationOpenSearchProperties properties) {
            return new ControlledEvidenceIndexPort(delegate, objectMapper, properties);
        }

        @Bean
        @Primary
        FailingDltKafkaTemplate failingDltKafkaTemplate(ProducerFactory<Object, Object> producerFactory) {
            return new FailingDltKafkaTemplate(producerFactory);
        }
    }

    @Autowired
    FailingDltKafkaTemplate template;

    @Autowired
    EvidenceRetrievalPort retrievalPort;

    @Autowired
    ControlledEvidenceIndexPort controlledIndexPort;

    @Autowired
    RecordingEmbeddingPort embeddingPort;

    @Autowired
    ProjectionValidator validator;

    @Autowired
    SafeEvidenceMapper evidenceMapper;

    @Autowired
    ObjectMapper objectMapper;

    @LocalServerPort
    int serverPort;

    @AfterEach
    void releaseTestControls() {
        controlledIndexPort.disarm();
        template.disarm();
        embeddingPort.clear();
    }

    @Test
    @Order(1)
    void productionPathKeepsProhibitedProjectionValuesOutOfEmbeddingsEvidenceLogsAndRest(
            CapturedOutput capturedOutput) throws Exception {
        String caseId = UUID.randomUUID().toString();
        String requestIdCanary = "11111111-1111-4111-8111-111111111111";
        String eventIdCanary = "22222222-2222-4222-8222-222222222222";
        String cardTokenCanary = "tok_CANARY_full_path_7c39";
        String cardFingerprintCanary = "fingerprint_CANARY_full_path_9a21";
        String accountCanary = "account_CANARY_full_path_2f84";
        String assigneeCanary = "assignee_CANARY_full_path_4b62";
        String resolutionActorCanary = "resolver_CANARY_full_path_1d05";
        String kafkaMetadataCanary = "kafka_metadata_CANARY_full_path_6e47";
        String infrastructureCanary = "http://internal-CANARY.invalid:9443/private";
        String credentialCanary = "sk-test-CANARY-never-a-real-secret-8f13";
        String rawVectorCanary = "vector_CANARY_full_path_3c70";
        String rawScoreCanary = "score_CANARY_full_path_5a98";
        String nestedRuleCanary = "nested_rule_CANARY_full_path_0e46";
        String ruleEvidenceSentinel = "RULE_EVIDENCE_LOG_SENTINEL_81dcb0";
        String rationaleSentinel = "RESOLUTION_RATIONALE_LOG_SENTINEL_b8a142";
        String questionSentinel = "ANALYST_QUESTION_LOG_SENTINEL_e1f704";

        FraudCaseProjectionEvent base = ProjectionFixtures.resolvedEvent(
                caseId, 4, "CONFIRMED_FRAUD", rationaleSentinel);
        FraudRuleEvidence firstRule = base.getSnapshot().getMatchedRules(0).toBuilder()
                .setEvidence(ruleEvidenceSentinel)
                .setUnknownFields(unknownFields(Map.of(801, nestedRuleCanary)))
                .build();
        FraudCaseProjectionSnapshot snapshot = base.getSnapshot().toBuilder()
                .setRequestId(requestIdCanary)
                .setAssigneeId(assigneeCanary)
                .setResolvedBy(resolutionActorCanary)
                .setMatchedRules(0, firstRule)
                .setUnknownFields(unknownFields(Map.of(
                        701, cardTokenCanary,
                        702, cardFingerprintCanary,
                        703, accountCanary,
                        704, infrastructureCanary,
                        705, credentialCanary,
                        706, rawVectorCanary,
                        707, rawScoreCanary)))
                .build();
        FraudCaseProjectionEvent event = ProjectionFixtures.withSnapshot(
                base.toBuilder().setEventId(eventIdCanary).build(), snapshot);
        String snapshotHash = event.getSnapshotHash();
        byte[] key = ProjectionFixtures.keyOf(event);
        ValidatedProjection validated = validator.validateProjection(key, event);
        List<EvidenceDraft> expectedDrafts = evidenceMapper.map(validated.snapshot());

        embeddingPort.clear();
        ProducerRecord<Object, Object> record = new ProducerRecord<>(TOPIC, key, event.toByteArray());
        record.headers().add("x-test-kafka-metadata", kafkaMetadataCanary.getBytes(StandardCharsets.UTF_8));
        send(record);
        await(() -> focalSizeIfComplete(caseId) == 2, Duration.ofSeconds(20));

        List<String> ingestionInputs = embeddingPort.inputs();
        assertEquals(expectedDrafts.stream().map(EvidenceDraft::text).toList(), ingestionInputs);
        List<float[]> ingestionVectors = embeddingPort.outputs();
        assertEquals(2, ingestionVectors.size());

        String rawDocuments = rawDocuments(caseId);
        JsonNode rawHits = objectMapper.readTree(rawDocuments).get("hits").get("hits");
        assertEquals(2, rawHits.size());
        List<String> indexedEvidence = new ArrayList<>();
        for (int index = 0; index < rawHits.size(); index++) {
            JsonNode source = rawHits.get(index).get("_source");
            indexedEvidence.add(source.get("text").asText());
            assertTrue(source.has("embedding"));
            assertEquals(snapshotHash, source.get("projectionIntegrity").asText());
        }
        assertEquals(Set.copyOf(expectedDrafts.stream().map(EvidenceDraft::text).toList()), Set.copyOf(indexedEvidence));

        String responseBody = postRetrieval(caseId, questionSentinel);
        assertTrue(responseBody.contains(ruleEvidenceSentinel), responseBody);
        assertTrue(responseBody.contains(rationaleSentinel), responseBody);

        List<String> prohibitedValues = List.of(
                requestIdCanary,
                cardTokenCanary,
                cardFingerprintCanary,
                accountCanary,
                assigneeCanary,
                resolutionActorCanary,
                eventIdCanary,
                kafkaMetadataCanary,
                infrastructureCanary,
                credentialCanary,
                rawVectorCanary,
                rawScoreCanary,
                nestedRuleCanary);
        String combinedIndexedEvidence = String.join("\n", indexedEvidence);
        for (String prohibited : prohibitedValues) {
            assertFalse(ingestionInputs.stream().anyMatch(input -> input.contains(prohibited)), prohibited);
            assertFalse(combinedIndexedEvidence.contains(prohibited), prohibited);
            assertFalse(responseBody.contains(prohibited), prohibited);
        }
        assertFalse(ingestionInputs.stream().anyMatch(input -> input.contains(snapshotHash)));
        assertFalse(combinedIndexedEvidence.contains(snapshotHash));
        assertFalse(responseBody.contains(snapshotHash));
        assertFalse(responseBody.contains(questionSentinel));
        assertFalse(responseBody.contains("\"embedding\""));
        assertFalse(responseBody.contains("\"projectionIntegrity\""));
        assertFalse(responseBody.contains("\"projectionVersion\""));
        assertFalse(responseBody.contains("\"score\""));

        String focalEvidence = expectedDrafts.stream()
                .filter(draft -> draft.sourceId().endsWith(":evidence"))
                .findFirst()
                .orElseThrow()
                .text();
        String retrievalText = questionSentinel + "\n\n" + focalEvidence;
        String completeVectorJson = objectMapper.writeValueAsString(ingestionVectors.get(0));
        String completeVectorArray = Arrays.toString(ingestionVectors.get(0));
        String logs = capturedOutput.getAll();
        List<String> logForbidden = new ArrayList<>(prohibitedValues);
        logForbidden.addAll(List.of(
                snapshotHash,
                questionSentinel,
                ruleEvidenceSentinel,
                rationaleSentinel,
                retrievalText,
                completeVectorJson,
                completeVectorArray,
                event.toString(),
                snapshot.toString()));
        for (String forbidden : logForbidden) {
            assertFalse(logs.contains(forbidden), () -> "captured logs contained protected value: " + forbidden);
        }
    }

    @Test
    @Order(2)
    void retryDoesNotCommitUntilDeterministicFailuresRecoverAndEvidenceIsIndexed() {
        String caseId = UUID.randomUUID().toString();
        FraudCaseProjectionEvent event = ProjectionFixtures.createdEvent(caseId, 0);
        ValidatedProjection validated = validator.validateProjection(ProjectionFixtures.keyOf(event), event);
        String expectedText = evidenceMapper.map(validated.snapshot()).getFirst().text();
        controlledIndexPort.failThenBlockSuccess(2);

        SendResult<Object, Object> sent = send(event);
        assertTrue(controlledIndexPort.awaitFinalAttempt(Duration.ofSeconds(15)), "final retry did not start");
        assertEquals(2, controlledIndexPort.failedAttempts());
        assertEquals(3, controlledIndexPort.totalAttempts());
        assertOffsetNotCommitted(sent.getRecordMetadata());

        controlledIndexPort.releaseSuccess();
        await(() -> focalSizeIfComplete(caseId) == 1, Duration.ofSeconds(20));
        awaitOffsetCommitted(sent.getRecordMetadata());

        List<EvidenceHit> finalEvidence = retrievalPort.loadFocal(caseId);
        assertEquals(1, finalEvidence.size());
        assertEquals("case:" + caseId + ":evidence", finalEvidence.getFirst().sourceId());
        assertEquals(expectedText, finalEvidence.getFirst().text());
        assertEquals(2, controlledIndexPort.failedAttempts());
        assertEquals(3, controlledIndexPort.totalAttempts());
        assertEquals(3, embeddingPort.inputs().size(), "each transient delivery attempt embeds once");
    }

    @Test
    @Order(3)
    void failedDltFutureKeepsSourceOffsetUncommittedUntilRedeliveryPublicationSucceeds() {
        String caseId = UUID.randomUUID().toString();
        FraudCaseProjectionEvent accepted = ProjectionFixtures.createdEvent(caseId, 0);
        SendResult<Object, Object> first = send(accepted);
        await(() -> focalSizeIfComplete(caseId) == 1, Duration.ofSeconds(20));
        awaitOffsetCommitted(first.getRecordMetadata());
        embeddingPort.clear();

        FraudCaseProjectionSnapshot conflictingSnapshot = accepted.getSnapshot().toBuilder()
                .setMerchantId("synthetic-conflicting-merchant")
                .build();
        FraudCaseProjectionEvent conflict = ProjectionFixtures.withSnapshot(accepted, conflictingSnapshot);
        assertFalse(accepted.getSnapshotHash().equals(conflict.getSnapshotHash()));
        validator.validateProjection(ProjectionFixtures.keyOf(conflict), conflict);
        controlledIndexPort.observeAssessments();
        byte[] conflictKey = ProjectionFixtures.keyOf(conflict);
        long dltStartOffset = dltEndOffset();
        template.arm(conflictKey);

        SendResult<Object, Object> sent = send(conflict);
        assertTrue(template.awaitFailedFuture(Duration.ofSeconds(15)), "DLT send future did not fail");
        assertOffsetNotCommitted(sent.getRecordMetadata());
        assertTrue(template.awaitRedeliveryPublish(Duration.ofSeconds(15)), "source record was not redelivered");
        assertEquals(2, controlledIndexPort.observedAssessments(),
                "a failed recovery publication must redeliver the source record");
        assertEquals(2, template.targetedSendAttempts());
        assertEquals(dltStartOffset, dltEndOffset(), "the failed DLT future must not append a record");
        assertOffsetNotCommitted(sent.getRecordMetadata());

        template.releaseSuccessfulPublish();
        await(() -> dltEndOffset() >= dltStartOffset + 1, Duration.ofSeconds(15));
        awaitOffsetCommitted(sent.getRecordMetadata());
        assertEquals(dltStartOffset + 1, dltEndOffset(), "exactly one recovered DLT record must be appended");
        ConsumerRecord<byte[], byte[]> dlt = consumeDltAt(dltStartOffset, Duration.ofSeconds(15));
        assertNotNull(dlt);
        assertArrayEquals(conflictKey, dlt.key());
        assertArrayEquals(conflict.toByteArray(), dlt.value());
        assertEquals(2, controlledIndexPort.observedAssessments(),
                "the permanent conflict must run once per source delivery");
        assertEquals(0, embeddingPort.inputs().size(), "integrity conflicts fail before embedding");

        List<EvidenceHit> finalEvidence = retrievalPort.loadFocal(caseId);
        assertEquals(1, finalEvidence.size());
        assertFalse(finalEvidence.getFirst().text().contains("synthetic-conflicting-merchant"));
    }

    @Test
    @Order(4)
    void exhaustedPartialWriteRetriesRemainRetrievalIneligibleUntilSuccessfulReplayRepairs() throws Exception {
        String caseId = UUID.randomUUID().toString();
        String oldResolution = "synthetic old resolution that must never mix with version two";
        FraudCaseProjectionEvent resolved = ProjectionFixtures.resolvedEvent(
                caseId, 1, "CONFIRMED_FRAUD", oldResolution);
        SendResult<Object, Object> initial = send(resolved);
        await(() -> focalSizeIfComplete(caseId) == 2, Duration.ofSeconds(20));
        awaitOffsetCommitted(initial.getRecordMetadata());

        FraudCaseProjectionEvent unresolved = ProjectionFixtures.claimedEvent(caseId, 2);
        ValidatedProjection validated = validator.validateProjection(
                ProjectionFixtures.keyOf(unresolved), unresolved);
        EvidenceDraft expectedEvidence = evidenceMapper.map(validated.snapshot()).getFirst();
        byte[] unresolvedKey = ProjectionFixtures.keyOf(unresolved);
        long dltStartOffset = dltEndOffset();
        controlledIndexPort.writePartialThenFail();

        SendResult<Object, Object> failed = send(unresolved);
        assertTrue(controlledIndexPort.awaitPartialWrite(Duration.ofSeconds(15)),
                "version-two evidence was not written");
        assertEquals(1, controlledIndexPort.partialAttempts());
        assertPhysicalSplit(caseId, unresolved, 1);
        assertIncompleteSnapshotHidden(caseId, expectedEvidence.text(), oldResolution);
        assertOffsetNotCommitted(failed.getRecordMetadata());

        controlledIndexPort.releasePartialFailures();
        await(() -> controlledIndexPort.partialAttempts() == 5, Duration.ofSeconds(15));
        await(() -> dltEndOffset() >= dltStartOffset + 1, Duration.ofSeconds(15));
        awaitOffsetCommitted(failed.getRecordMetadata());
        assertEquals(dltStartOffset + 1, dltEndOffset());
        ConsumerRecord<byte[], byte[]> dlt = consumeDltAt(dltStartOffset, Duration.ofSeconds(15));
        assertNotNull(dlt);
        assertArrayEquals(unresolvedKey, dlt.key());
        assertArrayEquals(unresolved.toByteArray(), dlt.value());
        assertEquals(5, controlledIndexPort.partialAttempts(),
                "the second-document failure must exhaust every configured attempt");
        assertPhysicalSplit(caseId, unresolved, 1);
        assertIncompleteSnapshotHidden(caseId, expectedEvidence.text(), oldResolution);

        controlledIndexPort.disarm();
        SendResult<Object, Object> replayed = send(unresolved);
        await(() -> focalSizeIfComplete(caseId) == 1, Duration.ofSeconds(20));
        awaitOffsetCommitted(replayed.getRecordMetadata());

        List<EvidenceHit> repaired = retrievalPort.loadFocal(caseId);
        assertEquals(1, repaired.size());
        assertEquals(expectedEvidence.text(), repaired.getFirst().text());
        assertFalse(repaired.getFirst().text().contains(oldResolution));
        JsonNode repairedEvidence = rawSource("case:" + caseId + ":evidence");
        JsonNode repairedResolution = rawSource("case:" + caseId + ":resolution");
        assertTrue(repairedEvidence.get("publicationComplete").asBoolean());
        assertFalse(repairedEvidence.get("resolutionExpected").asBoolean());
        assertEquals(2, repairedResolution.get("projectionVersion").asLong());
        assertEquals(unresolved.getSnapshotHash(), repairedResolution.get("projectionIntegrity").asText());
        assertEquals("ABSENT", repairedResolution.get("chunkState").asText());

        HttpResponse<String> repairedResponse = postRetrievalResponse(caseId, "review repaired evidence");
        assertEquals(200, repairedResponse.statusCode(), repairedResponse.body());
        assertTrue(repairedResponse.body().contains("merchant-review"), repairedResponse.body());
        assertFalse(repairedResponse.body().contains(oldResolution), repairedResponse.body());
        List<EvidenceHit> repairedRelated = retrievalPort.hybridSearch(
                UUID.randomUUID().toString(),
                expectedEvidence.text(),
                new DeterministicEmbeddingAdapter(1536).embed(expectedEvidence.text()),
                50);
        assertTrue(repairedRelated.stream().anyMatch(hit -> caseId.equals(hit.caseId())),
                "the repaired complete snapshot must be hybrid-retrieval eligible");
    }

    private SendResult<Object, Object> send(FraudCaseProjectionEvent event) {
        return send(new ProducerRecord<>(TOPIC, ProjectionFixtures.keyOf(event), event.toByteArray()));
    }

    private SendResult<Object, Object> send(ProducerRecord<Object, Object> record) {
        try {
            return template.send(record).get(10, TimeUnit.SECONDS);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private String rawDocuments(String caseId) throws Exception {
        String query = objectMapper.writeValueAsString(Map.of(
                "size", 10,
                "query", Map.of("term", Map.of("caseId", caseId))));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(openSearchUrl() + "/investigation-kafka-it-v1/_search"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(query))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private JsonNode rawSource(String sourceId) throws Exception {
        String encodedId = URLEncoder.encode(sourceId, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(openSearchUrl() + "/investigation-kafka-it-v1/_doc/" + encodedId))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return objectMapper.readTree(response.body()).get("_source");
    }

    private void assertPhysicalSplit(
            String caseId, FraudCaseProjectionEvent incomplete, long oldResolutionVersion) throws Exception {
        JsonNode evidence = rawSource("case:" + caseId + ":evidence");
        JsonNode resolution = rawSource("case:" + caseId + ":resolution");
        assertEquals(2, evidence.get("projectionVersion").asLong());
        assertEquals(incomplete.getSnapshotHash(), evidence.get("projectionIntegrity").asText());
        assertEquals("ACTIVE", evidence.get("chunkState").asText());
        assertFalse(evidence.get("publicationComplete").asBoolean());
        assertFalse(evidence.get("resolutionExpected").asBoolean());
        assertEquals(oldResolutionVersion, resolution.get("projectionVersion").asLong());
        assertEquals("ACTIVE", resolution.get("chunkState").asText());
    }

    private void assertIncompleteSnapshotHidden(String caseId, String evidenceText, String oldResolution)
            throws Exception {
        EvidenceStoreUnavailableException unavailable = assertThrows(
                EvidenceStoreUnavailableException.class,
                () -> retrievalPort.loadFocal(caseId));
        assertFalse(unavailable.getMessage().contains(evidenceText));
        assertFalse(unavailable.getMessage().contains(oldResolution));

        List<EvidenceHit> related = retrievalPort.hybridSearch(
                UUID.randomUUID().toString(),
                evidenceText,
                new DeterministicEmbeddingAdapter(1536).embed(evidenceText),
                50);
        assertFalse(related.stream().anyMatch(hit -> caseId.equals(hit.caseId())),
                "an incomplete case must be excluded from hybrid retrieval");

        HttpResponse<String> response = postRetrievalResponse(caseId, "review incomplete evidence");
        assertEquals(503, response.statusCode(), response.body());
        assertTrue(response.body().contains("INVESTIGATION_RETRIEVAL_UNAVAILABLE"), response.body());
        assertFalse(response.body().contains(evidenceText), response.body());
        assertFalse(response.body().contains(oldResolution), response.body());
    }

    private String postRetrieval(String caseId, String question) throws Exception {
        HttpResponse<String> response = postRetrievalResponse(caseId, question);
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private HttpResponse<String> postRetrievalResponse(String caseId, String question) throws Exception {
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "question", question,
                "maxRelatedCases", 5));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + serverPort
                        + "/api/v1/fraud-cases/" + caseId + "/investigation/retrieval"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private int focalSizeIfComplete(String caseId) {
        try {
            return retrievalPort.loadFocal(caseId).size();
        } catch (EvidenceStoreUnavailableException incomplete) {
            return -1;
        }
    }

    private void assertOffsetNotCommitted(RecordMetadata metadata) {
        long committed = committedOffset(metadata.topic(), metadata.partition());
        assertTrue(committed < metadata.offset() + 1,
                () -> "source offset was committed before recovery: committed=" + committed
                        + ", record=" + metadata.offset());
    }

    private void awaitOffsetCommitted(RecordMetadata metadata) {
        await(() -> committedOffset(metadata.topic(), metadata.partition()) >= metadata.offset() + 1,
                Duration.ofSeconds(15));
    }

    private long committedOffset(String topic, int partition) {
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets = admin
                    .listConsumerGroupOffsets(GROUP_ID)
                    .partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS);
            org.apache.kafka.clients.consumer.OffsetAndMetadata offset =
                    offsets.get(new TopicPartition(topic, partition));
            return offset == null ? -1 : offset.offset();
        } catch (Exception error) {
            throw new IllegalStateException("Could not inspect the investigation consumer offset", error);
        }
    }

    private long dltEndOffset() {
        Properties properties = dltConsumerProperties();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(properties)) {
            TopicPartition partition = new TopicPartition(DLT_TOPIC, 0);
            return consumer.endOffsets(List.of(partition), Duration.ofSeconds(5)).get(partition);
        }
    }

    private ConsumerRecord<byte[], byte[]> consumeDltAt(long offset, Duration timeout) {
        Properties properties = dltConsumerProperties();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(properties)) {
            TopicPartition partition = new TopicPartition(DLT_TOPIC, 0);
            consumer.assign(List.of(partition));
            consumer.seek(partition, offset);
            long end = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < end) {
                for (ConsumerRecord<byte[], byte[]> record : consumer.poll(Duration.ofMillis(200))) {
                    if (record.offset() == offset) {
                        return record;
                    }
                }
            }
        }
        return null;
    }

    private Properties dltConsumerProperties() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return properties;
    }

    private static UnknownFieldSet unknownFields(Map<Integer, String> values) {
        UnknownFieldSet.Builder fields = UnknownFieldSet.newBuilder();
        values.forEach((number, value) -> fields.addField(
                number,
                UnknownFieldSet.Field.newBuilder()
                        .addLengthDelimited(ByteString.copyFromUtf8(value))
                        .build()));
        return fields.build();
    }

    private static String openSearchUrl() {
        return "http://" + openSearch.getHost() + ":" + openSearch.getMappedPort(9200);
    }

    private static void await(BooleanSupplier condition, Duration timeout) {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for condition");
            }
        }
        fail("condition not met within " + timeout);
    }

    static final class RecordingEmbeddingPort implements EmbeddingPort {
        private final DeterministicEmbeddingAdapter delegate;
        private final List<String> inputs = new CopyOnWriteArrayList<>();
        private final List<float[]> outputs = new CopyOnWriteArrayList<>();

        RecordingEmbeddingPort(int dimensions) {
            delegate = new DeterministicEmbeddingAdapter(dimensions);
        }

        @Override
        public float[] embed(String text) {
            float[] output = delegate.embed(text);
            inputs.add(text);
            outputs.add(output.clone());
            return output;
        }

        List<String> inputs() {
            return List.copyOf(inputs);
        }

        List<float[]> outputs() {
            return outputs.stream().map(float[]::clone).toList();
        }

        void clear() {
            inputs.clear();
            outputs.clear();
        }
    }

    static final class ControlledEvidenceIndexPort implements EvidenceIndexPort {
        private final EvidenceIndexPort delegate;
        private final EvidenceIndexPort partialFailureStore;
        private final AtomicReference<FailurePlan> failurePlan = new AtomicReference<>();
        private final AtomicReference<PartialFailurePlan> partialFailurePlan = new AtomicReference<>();
        private final AtomicInteger observedAssessments = new AtomicInteger(-1);

        ControlledEvidenceIndexPort(
                EvidenceIndexPort delegate,
                ObjectMapper objectMapper,
                InvestigationOpenSearchProperties properties) {
            this.delegate = delegate;
            RestClient failResolutionWrites = RestClient.builder()
                    .baseUrl(properties.url())
                    .requestInterceptor((request, body, execution) -> {
                        PartialFailurePlan plan = partialFailurePlan.get();
                        String decodedPath = URLDecoder.decode(
                                request.getURI().getRawPath(), StandardCharsets.UTF_8);
                        if (plan != null
                                && request.getMethod() == HttpMethod.PUT
                                && decodedPath.endsWith(":resolution")) {
                            if (plan.attempts.get() == 1) {
                                plan.partialWriteCompleted.countDown();
                                try {
                                    if (!plan.releaseFailures.await(30, TimeUnit.SECONDS)) {
                                        throw new java.io.IOException(
                                                "Timed out waiting to release deterministic partial failures");
                                    }
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new java.io.IOException(
                                            "Interrupted while waiting to release deterministic partial failures",
                                            interrupted);
                                }
                            }
                            throw new java.io.IOException("Deterministic resolution write failure");
                        }
                        return execution.execute(request, body);
                    })
                    .build();
            this.partialFailureStore = new OpenSearchEvidenceIndexStore(
                    failResolutionWrites, objectMapper, properties);
        }

        @Override
        public OptionalLong currentVersion(String sourceId) {
            return delegate.currentVersion(sourceId);
        }

        @Override
        public void index(EvidenceDraft draft, float[] embedding) {
            delegate.index(draft, embedding);
        }

        @Override
        public ProjectionAssessment assessProjection(
                ValidatedProjection projection, List<EvidenceDraft> expectedDrafts) {
            if (observedAssessments.get() >= 0) {
                observedAssessments.incrementAndGet();
            }
            return delegate.assessProjection(projection, expectedDrafts);
        }

        @Override
        public void indexProjection(
                ValidatedProjection projection,
                List<EvidenceDraft> drafts,
                List<float[]> embeddings) {
            PartialFailurePlan partialPlan = partialFailurePlan.get();
            if (partialPlan != null) {
                partialPlan.attempts.incrementAndGet();
                partialFailureStore.indexProjection(projection, drafts, embeddings);
                throw new EvidenceStoreUnavailableException(
                        "Deterministic resolution failure plan unexpectedly completed");
            }

            FailurePlan plan = failurePlan.get();
            if (plan == null) {
                delegate.indexProjection(projection, drafts, embeddings);
                return;
            }
            int attempt = plan.totalAttempts.incrementAndGet();
            if (attempt <= plan.failuresRequired) {
                plan.failedAttempts.incrementAndGet();
                throw new EvidenceStoreUnavailableException("Deterministic test index failure");
            }
            plan.finalAttemptEntered.countDown();
            try {
                if (!plan.releaseSuccess.await(15, TimeUnit.SECONDS)) {
                    throw new EvidenceStoreUnavailableException("Timed out waiting to release test indexing");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new EvidenceStoreUnavailableException("Interrupted while waiting to release test indexing");
            }
            delegate.indexProjection(projection, drafts, embeddings);
        }

        void failThenBlockSuccess(int failureCount) {
            failurePlan.set(new FailurePlan(failureCount));
        }

        void writePartialThenFail() {
            partialFailurePlan.set(new PartialFailurePlan());
        }

        boolean awaitPartialWrite(Duration timeout) {
            PartialFailurePlan plan = requiredPartialPlan();
            try {
                return plan.partialWriteCompleted.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        void releasePartialFailures() {
            PartialFailurePlan plan = partialFailurePlan.get();
            if (plan != null) {
                plan.releaseFailures.countDown();
            }
        }

        int partialAttempts() {
            return requiredPartialPlan().attempts.get();
        }

        boolean awaitFinalAttempt(Duration timeout) {
            FailurePlan plan = requiredPlan();
            try {
                return plan.finalAttemptEntered.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        void releaseSuccess() {
            FailurePlan plan = failurePlan.get();
            if (plan != null) {
                plan.releaseSuccess.countDown();
            }
        }

        int failedAttempts() {
            return requiredPlan().failedAttempts.get();
        }

        int totalAttempts() {
            return requiredPlan().totalAttempts.get();
        }

        void observeAssessments() {
            observedAssessments.set(0);
        }

        int observedAssessments() {
            return observedAssessments.get();
        }

        void disarm() {
            releaseSuccess();
            releasePartialFailures();
            failurePlan.set(null);
            partialFailurePlan.set(null);
            observedAssessments.set(-1);
        }

        private FailurePlan requiredPlan() {
            FailurePlan plan = failurePlan.get();
            if (plan == null) {
                throw new IllegalStateException("No deterministic failure plan is armed");
            }
            return plan;
        }

        private PartialFailurePlan requiredPartialPlan() {
            PartialFailurePlan plan = partialFailurePlan.get();
            if (plan == null) {
                throw new IllegalStateException("No deterministic partial failure plan is armed");
            }
            return plan;
        }

        private static final class FailurePlan {
            private final int failuresRequired;
            private final AtomicInteger totalAttempts = new AtomicInteger();
            private final AtomicInteger failedAttempts = new AtomicInteger();
            private final CountDownLatch finalAttemptEntered = new CountDownLatch(1);
            private final CountDownLatch releaseSuccess = new CountDownLatch(1);

            private FailurePlan(int failuresRequired) {
                this.failuresRequired = failuresRequired;
            }
        }

        private static final class PartialFailurePlan {
            private final AtomicInteger attempts = new AtomicInteger();
            private final CountDownLatch partialWriteCompleted = new CountDownLatch(1);
            private final CountDownLatch releaseFailures = new CountDownLatch(1);
        }
    }

}
