package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.out.opensearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudCaseProjectionEvent;
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudCaseProjectionSnapshot;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.ProjectionIngestionService;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.ProjectionValidator;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.SafeEvidenceMapper;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EmbeddingPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EvidenceStoreUnavailableException;
import io.github.monssifechadli99.transactiq.investigation_assistant.configuration.InvestigationOpenSearchProperties;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceHit;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.ProjectionIntegrityException;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.ValidatedProjection;
import io.github.monssifechadli99.transactiq.investigation_assistant.support.DeterministicEmbeddingAdapter;
import io.github.monssifechadli99.transactiq.investigation_assistant.support.ProjectionFixtures;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenSearchProjectionIntegrityIntegrationTest {

    private static final int DIMENSIONS = 1536;

    @Container
    static final GenericContainer<?> openSearch =
            new GenericContainer<>(DockerImageName.parse("opensearchproject/opensearch:3.2.0"))
                    .withEnv("discovery.type", "single-node")
                    .withEnv("DISABLE_SECURITY_PLUGIN", "true")
                    .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
                    .withExposedPorts(9200);

    private final ProjectionValidator validator = new ProjectionValidator();
    private final SafeEvidenceMapper safeMapper = new SafeEvidenceMapper();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private String baseUrl;
    private RestClient client;
    private InvestigationOpenSearchProperties properties;
    private OpenSearchEvidenceIndexStore store;

    @BeforeAll
    void initialize() {
        baseUrl = "http://" + openSearch.getHost() + ":" + openSearch.getMappedPort(9200);
        client = RestClient.builder().baseUrl(baseUrl).build();
        properties = new InvestigationOpenSearchProperties(
                baseUrl,
                Duration.ofSeconds(5),
                "projection-integrity-evidence-v1",
                "projection-integrity-evidence-read",
                "projection-integrity-evidence-write",
                "projection-integrity-evidence-hybrid-v1");
        new OpenSearchEvidenceIndexInitializer(client, properties, jsonMapper).afterPropertiesSet();
        store = new OpenSearchEvidenceIndexStore(client, jsonMapper, properties);
    }

    @Test
    void sequentialSameSnapshotIsDuplicateButDifferentSnapshotIsPermanentConflict() {
        String duplicateCaseId = UUID.randomUUID().toString();
        FraudCaseProjectionEvent duplicateEvent = ProjectionFixtures.createdEvent(duplicateCaseId, 4);
        CountingEmbeddingPort duplicateEmbedding = new CountingEmbeddingPort(DIMENSIONS);
        ProjectionIngestionService duplicateService = service(store, duplicateEmbedding);

        duplicateService.ingest(validated(duplicateEvent));
        duplicateService.ingest(validated(duplicateEvent));

        assertEquals(1, duplicateEmbedding.callCount(), "complete duplicate must skip embedding");

        String conflictCaseId = UUID.randomUUID().toString();
        FraudCaseProjectionEvent first = withMerchant(
                ProjectionFixtures.createdEvent(conflictCaseId, 7), "merchant-integrity-a");
        FraudCaseProjectionEvent conflicting = withMerchant(first, "merchant-integrity-b");
        CountingEmbeddingPort conflictEmbedding = new CountingEmbeddingPort(DIMENSIONS);
        ProjectionIngestionService conflictService = service(store, conflictEmbedding);
        conflictService.ingest(validated(first));

        assertThrows(ProjectionIntegrityException.class, () -> conflictService.ingest(validated(conflicting)));
        assertEquals(1, conflictEmbedding.callCount(), "conflict must be detected before another embedding");
        assertTrue(((String) rawSource(evidenceId(conflictCaseId)).get("text"))
                .contains("merchant-integrity-a"));
    }

    @Test
    void concurrentSameSnapshotConvergesAndDifferentSnapshotsConflict() throws Exception {
        String duplicateCaseId = UUID.randomUUID().toString();
        FraudCaseProjectionEvent duplicate = ProjectionFixtures.resolvedEvent(
                duplicateCaseId, 5, "CONFIRMED_FRAUD", "synthetic concurrent duplicate");
        ProjectionIngestionService duplicateService = service(store, new CountingEmbeddingPort(DIMENSIONS));

        List<Throwable> duplicateResults = runConcurrently(
                () -> duplicateService.ingest(validated(duplicate)),
                () -> duplicateService.ingest(validated(duplicate)));

        assertNull(duplicateResults.get(0));
        assertNull(duplicateResults.get(1));
        assertEquals(5L, ((Number) rawSource(evidenceId(duplicateCaseId)).get("projectionVersion")).longValue());
        assertEquals(5L, ((Number) rawSource(resolutionId(duplicateCaseId)).get("projectionVersion")).longValue());

        String conflictCaseId = UUID.randomUUID().toString();
        FraudCaseProjectionEvent first = withMerchant(
                ProjectionFixtures.resolvedEvent(
                        conflictCaseId, 8, "CONFIRMED_FRAUD", "synthetic concurrent conflict"),
                "merchant-concurrent-a");
        FraudCaseProjectionEvent second = withMerchant(first, "merchant-concurrent-b");
        ProjectionIngestionService conflictService = service(store, new CountingEmbeddingPort(DIMENSIONS));

        List<Throwable> conflictResults = runConcurrently(
                () -> conflictService.ingest(validated(first)),
                () -> conflictService.ingest(validated(second)));

        assertEquals(1, conflictResults.stream().filter(result -> result == null).count());
        Throwable conflict = conflictResults.stream().filter(result -> result != null).findFirst().orElseThrow();
        assertInstanceOf(ProjectionIntegrityException.class, conflict);
        String finalText = (String) rawSource(evidenceId(conflictCaseId)).get("text");
        assertTrue(finalText.contains("merchant-concurrent-a") || finalText.contains("merchant-concurrent-b"));
    }

    @Test
    void concurrentLowerResolvedAndHigherUnresolvedAlwaysLeaveHighestEvidence() throws Exception {
        for (int iteration = 0; iteration < 4; iteration++) {
            String caseId = UUID.randomUUID().toString();
            FraudCaseProjectionEvent lower = ProjectionFixtures.resolvedEvent(
                    caseId, 2, "FALSE_POSITIVE", "synthetic obsolete resolution " + iteration);
            FraudCaseProjectionEvent higher = withMerchant(
                    ProjectionFixtures.createdEvent(caseId, 6), "merchant-highest-" + iteration);
            ProjectionIngestionService service = service(store, new CountingEmbeddingPort(DIMENSIONS));

            List<Throwable> results = runConcurrently(
                    () -> service.ingest(validated(lower)),
                    () -> service.ingest(validated(higher)));

            assertNull(results.get(0));
            assertNull(results.get(1));
            Map<String, Object> evidence = rawSource(evidenceId(caseId));
            assertEquals(6L, ((Number) evidence.get("projectionVersion")).longValue());
            assertTrue(((String) evidence.get("text")).contains("merchant-highest-" + iteration));
            assertResolutionAbsentAtVersion(caseId, 6);
        }
    }

    @Test
    void partialResolvedWriteIsRepairedOnRetry() {
        AtomicBoolean failResolutionOnce = new AtomicBoolean(true);
        AtomicInteger resolutionAttempts = new AtomicInteger();
        RestClient failOnceClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    String decodedPath = URLDecoder.decode(
                            request.getURI().getRawPath(), StandardCharsets.UTF_8);
                    if (request.getMethod() == HttpMethod.PUT && decodedPath.endsWith(":resolution")) {
                        resolutionAttempts.incrementAndGet();
                        if (failResolutionOnce.compareAndSet(true, false)) {
                            throw new IOException("synthetic fail-once resolution write");
                        }
                    }
                    return execution.execute(request, body);
                })
                .build();
        OpenSearchEvidenceIndexStore failOnceStore =
                new OpenSearchEvidenceIndexStore(failOnceClient, jsonMapper, properties);
        CountingEmbeddingPort embedding = new CountingEmbeddingPort(DIMENSIONS);
        ProjectionIngestionService service = service(failOnceStore, embedding);
        String caseId = UUID.randomUUID().toString();
        FraudCaseProjectionEvent event = ProjectionFixtures.resolvedEvent(
                caseId, 3, "CONFIRMED_FRAUD", "synthetic repairable resolution");
        ValidatedProjection projection = validated(event);

        assertThrows(RuntimeException.class, () -> service.ingest(projection));
        Map<String, Object> incompleteEvidence = rawSource(evidenceId(caseId));
        assertEquals(3L, ((Number) incompleteEvidence.get("projectionVersion")).longValue());
        assertEquals(false, incompleteEvidence.get("publicationComplete"));
        assertEquals(true, incompleteEvidence.get("resolutionExpected"));
        assertThrows(
                org.springframework.web.client.HttpClientErrorException.NotFound.class,
                () -> rawSource(resolutionId(caseId)));
        OpenSearchEvidenceRetrievalStore retrievalStore =
                new OpenSearchEvidenceRetrievalStore(client, jsonMapper, properties);
        assertThrows(EvidenceStoreUnavailableException.class, () -> retrievalStore.loadFocal(caseId));

        service.ingest(projection);

        assertEquals(2, resolutionAttempts.get());
        assertEquals(4, embedding.callCount(), "partial retry must not be classified as a complete duplicate");
        Map<String, Object> resolution = rawSource(resolutionId(caseId));
        assertEquals("ACTIVE", resolution.get("chunkState"));
        assertTrue(((String) resolution.get("text")).contains("synthetic repairable resolution"));
        Map<String, Object> publishedEvidence = rawSource(evidenceId(caseId));
        assertEquals(true, publishedEvidence.get("publicationComplete"));
        assertEquals(true, publishedEvidence.get("resolutionExpected"));
        assertEquals(2, retrievalStore.loadFocal(caseId).size());
    }

    @Test
    void newerUnresolvedProjectionRemovesObsoleteResolutionFromRetrieval() {
        String caseId = UUID.randomUUID().toString();
        ProjectionIngestionService service = service(store, new CountingEmbeddingPort(DIMENSIONS));
        service.ingest(validated(ProjectionFixtures.resolvedEvent(
                caseId, 1, "FALSE_POSITIVE", "synthetic obsolete rationale")));

        service.ingest(validated(ProjectionFixtures.createdEvent(caseId, 2)));

        assertResolutionAbsentAtVersion(caseId, 2);
        List<EvidenceHit> focal = new OpenSearchEvidenceRetrievalStore(client, jsonMapper, properties)
                .loadFocal(caseId);
        assertEquals(1, focal.size());
        assertEquals(evidenceId(caseId), focal.getFirst().sourceId());
        assertFalse(focal.getFirst().text().contains("synthetic obsolete rationale"));
    }

    private ProjectionIngestionService service(
            OpenSearchEvidenceIndexStore targetStore, EmbeddingPort embedding) {
        return new ProjectionIngestionService(safeMapper, embedding, targetStore, DIMENSIONS);
    }

    private ValidatedProjection validated(FraudCaseProjectionEvent event) {
        return validator.validateProjection(ProjectionFixtures.keyOf(event), event);
    }

    private FraudCaseProjectionEvent withMerchant(FraudCaseProjectionEvent event, String merchantId) {
        FraudCaseProjectionSnapshot snapshot = event.getSnapshot().toBuilder()
                .setMerchantId(merchantId)
                .build();
        return ProjectionFixtures.withSnapshot(event, snapshot);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rawSource(String sourceId) {
        String body = client.get().uri("/{alias}/_doc/{id}", properties.readAlias(), sourceId)
                .retrieve().body(String.class);
        Map<String, Object> root = jsonMapper.readValue(body, Map.class);
        return (Map<String, Object>) root.get("_source");
    }

    private void assertResolutionAbsentAtVersion(String caseId, long expectedVersion) {
        Map<String, Object> tombstone = rawSource(resolutionId(caseId));
        assertEquals(expectedVersion, ((Number) tombstone.get("projectionVersion")).longValue());
        assertEquals("ABSENT", tombstone.get("chunkState"));
        assertFalse(tombstone.containsKey("sourceType"));
        assertFalse(tombstone.containsKey("caseId"));
        assertFalse(tombstone.containsKey("text"));
        assertFalse(tombstone.containsKey("embedding"));
    }

    private static List<Throwable> runConcurrently(Runnable first, Runnable second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Throwable> firstResult = executor.submit(concurrent(ready, start, first));
            Future<Throwable> secondResult = executor.submit(concurrent(ready, start, second));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return java.util.Arrays.asList(
                    firstResult.get(30, TimeUnit.SECONDS),
                    secondResult.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private static Callable<Throwable> concurrent(
            CountDownLatch ready, CountDownLatch start, Runnable operation) {
        return () -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            try {
                operation.run();
                return null;
            } catch (Throwable error) {
                return error;
            }
        };
    }

    private static String evidenceId(String caseId) {
        return "case:" + caseId + ":evidence";
    }

    private static String resolutionId(String caseId) {
        return "case:" + caseId + ":resolution";
    }

    private static final class CountingEmbeddingPort implements EmbeddingPort {
        private final DeterministicEmbeddingAdapter delegate;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingEmbeddingPort(int dimensions) {
            delegate = new DeterministicEmbeddingAdapter(dimensions);
        }

        @Override
        public float[] embed(String text) {
            calls.incrementAndGet();
            return delegate.embed(text);
        }

        private int callCount() {
            return calls.get();
        }
    }
}
