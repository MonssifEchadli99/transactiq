package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.out.opensearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.investigation_assistant.application.InvestigationRetrievalService;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.ProjectionIngestionService;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.ProjectionValidator;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.SafeEvidenceMapper;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EmbeddingPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EvidenceStoreUnavailableException;
import io.github.monssifechadli99.transactiq.investigation_assistant.configuration.InvestigationOpenSearchProperties;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceDraft;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceHit;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceSourceType;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.InvestigationRetrievalResult;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.RelatedCaseGroup;
import io.github.monssifechadli99.transactiq.investigation_assistant.support.DeterministicEmbeddingAdapter;
import io.github.monssifechadli99.transactiq.investigation_assistant.support.ProjectionFixtures;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenSearchEvidenceStoreIntegrationTest {

    private static final int DIMENSIONS = 1536;

    @Container
    static final GenericContainer<?> openSearch =
            new GenericContainer<>(DockerImageName.parse("opensearchproject/opensearch:3.2.0"))
                    .withEnv("discovery.type", "single-node")
                    .withEnv("DISABLE_SECURITY_PLUGIN", "true")
                    .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
                    .withExposedPorts(9200);

    private RestClient client;
    private JsonMapper mapper;
    private InvestigationOpenSearchProperties properties;
    private OpenSearchEvidenceIndexStore indexStore;
    private OpenSearchEvidenceRetrievalStore retrievalStore;
    private final EmbeddingPort embedding = new DeterministicEmbeddingAdapter(DIMENSIONS);

    @BeforeAll
    void initialize() {
        String baseUrl = "http://" + openSearch.getHost() + ":" + openSearch.getMappedPort(9200);
        client = RestClient.builder().baseUrl(baseUrl).build();
        mapper = JsonMapper.builder().build();
        properties = new InvestigationOpenSearchProperties(
                baseUrl, Duration.ofSeconds(5), "evidence-store-v1",
                "evidence-store-read", "evidence-store-write", "evidence-store-hybrid-v1");
        new OpenSearchEvidenceIndexInitializer(client, properties, mapper).afterPropertiesSet();
        new OpenSearchEvidenceIndexInitializer(client, properties, mapper).afterPropertiesSet();
        indexStore = new OpenSearchEvidenceIndexStore(client, mapper, properties);
        retrievalStore = new OpenSearchEvidenceRetrievalStore(client, mapper, properties);
    }

    private EvidenceDraft evidenceDraft(String caseId, long version, String text) {
        return new EvidenceDraft("case:" + caseId + ":evidence", EvidenceSourceType.CASE_EVIDENCE, caseId, text, version);
    }

    @Test
    void duplicateAndStaleWritesConvergeOnTheLatestVersion() {
        String caseId = UUID.randomUUID().toString();
        indexStore.index(evidenceDraft(caseId, 0, "initial synthetic evidence"), embedding.embed("initial"));
        indexStore.index(evidenceDraft(caseId, 0, "initial synthetic evidence"), embedding.embed("initial"));
        indexStore.index(evidenceDraft(caseId, 3, "latest synthetic evidence"), embedding.embed("latest"));
        indexStore.index(evidenceDraft(caseId, 1, "stale synthetic evidence"), embedding.embed("stale"));

        OptionalLong version = indexStore.currentVersion("case:" + caseId + ":evidence");
        assertEquals(3L, version.orElseThrow());
        List<EvidenceHit> focal = retrievalStore.loadFocal(caseId);
        assertEquals(1, focal.size());
        assertEquals("latest synthetic evidence", focal.get(0).text());
    }

    @Test
    void strictMappingRejectsUndeclaredFields() {
        HttpClientErrorException error = assertThrows(HttpClientErrorException.class, () ->
                client.put().uri("/{alias}/_doc/strict-rejection-case", properties.writeAlias())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("sourceId", "strict-rejection-case", "undeclaredField", "forbidden"))
                        .retrieve().toBodilessEntity());
        assertTrue(error.getStatusCode().is4xxClientError());
    }

    @Test
    void loadFocalReturnsEvidenceThenResolutionSortedDeterministically() {
        String caseId = UUID.randomUUID().toString();
        var event = ProjectionFixtures.resolvedEvent(
                caseId, 1, "CONFIRMED_FRAUD", "synthetic resolution rationale");
        ProjectionValidator validator = new ProjectionValidator();
        new ProjectionIngestionService(new SafeEvidenceMapper(), embedding, indexStore, DIMENSIONS)
                .ingest(validator.validateProjection(ProjectionFixtures.keyOf(event), event));

        List<EvidenceHit> focal = retrievalStore.loadFocal(caseId);

        assertEquals(2, focal.size());
        assertEquals(EvidenceSourceType.CASE_EVIDENCE, focal.get(0).sourceType());
        assertEquals(EvidenceSourceType.RESOLUTION, focal.get(1).sourceType());
    }

    @Test
    void loadFocalIsEmptyWhenNothingIsIndexedYet() {
        assertTrue(retrievalStore.loadFocal(UUID.randomUUID().toString()).isEmpty());
    }

    @Test
    void initializerAddsOnlyTheMissingExpectedAliasAndRemainsIdempotent() {
        InvestigationOpenSearchProperties isolated = uniqueProperties("missing-alias");
        createRawIndex(isolated.physicalIndex(), expectedIndexDefinition());
        addAlias(isolated.physicalIndex(), isolated.readAlias(), false);

        OpenSearchEvidenceIndexInitializer initializer =
                new OpenSearchEvidenceIndexInitializer(client, isolated, mapper);
        initializer.afterPropertiesSet();
        initializer.afterPropertiesSet();

        assertEquals(Set.of(isolated.physicalIndex()), aliasTargets(isolated.readAlias()));
        assertEquals(Set.of(isolated.physicalIndex()), aliasTargets(isolated.writeAlias()));
        String writeAliasJson = client.get().uri("/_alias/{alias}", isolated.writeAlias())
                .retrieve().body(String.class);
        assertTrue(mapper.readTree(writeAliasJson)
                .get(isolated.physicalIndex()).get("aliases").get(isolated.writeAlias())
                .get("is_write_index").asBoolean());
    }

    @Test
    void initializerRejectsAnExistingIndexWithoutKnnEnabledBeforeAddingAliases() {
        InvestigationOpenSearchProperties isolated = uniqueProperties("knn-disabled");
        createRawIndex(isolated.physicalIndex(), """
                {"settings":{"index.knn":false},"mappings":{"dynamic":"strict","properties":{
                "embedding":{"type":"knn_vector","dimension":1536}}}}
                """);

        IllegalArgumentException incompatibility = assertThrows(IllegalArgumentException.class,
                () -> new OpenSearchEvidenceIndexInitializer(client, isolated, mapper).afterPropertiesSet());
        assertTrue(incompatibility.getMessage().contains("vector search is not enabled"));
        assertThrows(HttpClientErrorException.NotFound.class,
                () -> client.get().uri("/_alias/{alias}", isolated.readAlias()).retrieve().toBodilessEntity());
        assertThrows(HttpClientErrorException.NotFound.class,
                () -> client.get().uri("/_alias/{alias}", isolated.writeAlias()).retrieve().toBodilessEntity());
    }

    @Test
    void initializerRejectsIncompatibleStrictnessDimensionAndVectorSpace() {
        String expected = expectedIndexDefinition();
        List<String> incompatibleDefinitions = List.of(
                expected.replace("\"dynamic\": \"strict\"", "\"dynamic\": false"),
                expected.replace("\"dimension\": 1536", "\"dimension\": 768"),
                expected.replace("\"cosinesimil\"", "\"l2\""));

        for (int variant = 0; variant < incompatibleDefinitions.size(); variant++) {
            InvestigationOpenSearchProperties isolated = uniqueProperties("mapping-" + variant);
            createRawIndex(isolated.physicalIndex(), incompatibleDefinitions.get(variant));

            assertThrows(IllegalArgumentException.class,
                    () -> new OpenSearchEvidenceIndexInitializer(client, isolated, mapper).afterPropertiesSet());
        }
    }

    @Test
    void initializerRejectsAnUnexpectedAliasTargetWithoutRepointingIt() {
        InvestigationOpenSearchProperties isolated = uniqueProperties("unexpected-alias");
        createRawIndex(isolated.physicalIndex(), expectedIndexDefinition());
        String incompatibleIndex = isolated.physicalIndex() + "-incompatible";
        createRawIndex(incompatibleIndex, """
                {"settings":{"index.knn":false},"mappings":{"dynamic":"strict","properties":{}}}
                """);
        addAlias(incompatibleIndex, isolated.readAlias(), false);

        assertThrows(IllegalArgumentException.class,
                () -> new OpenSearchEvidenceIndexInitializer(client, isolated, mapper).afterPropertiesSet());

        assertEquals(Set.of(incompatibleIndex), aliasTargets(isolated.readAlias()));
        assertFalse(aliasTargets(isolated.readAlias()).contains(isolated.physicalIndex()));
        assertThrows(HttpClientErrorException.NotFound.class,
                () -> client.get().uri("/_alias/{alias}", isolated.writeAlias()).retrieve().toBodilessEntity());
    }

    @Test
    void hybridControlsProveCandidateSelectionAndOpenSearchRrfFusionBeforeGrouping() {
        InvestigationOpenSearchProperties isolated = uniqueProperties("controlled-hybrid");
        new OpenSearchEvidenceIndexInitializer(client, isolated, mapper).afterPropertiesSet();
        OpenSearchEvidenceRetrievalStore isolatedRetrievalStore =
                new OpenSearchEvidenceRetrievalStore(client, mapper, isolated);

        String focalCaseId = UUID.randomUUID().toString();
        String lexicalCaseId = UUID.randomUUID().toString();
        String semanticCaseId = UUID.randomUUID().toString();
        String fusionCaseId = UUID.randomUUID().toString();
        String semanticDistractorCaseId = UUID.randomUUID().toString();
        String queryText = "kineticamber cobaltpattern";
        float[] queryVector = controlledVector(1.0f, 0.0f);
        int knnWindow = 4;

        indexControlled(isolated, focalCaseId, EvidenceSourceType.CASE_EVIDENCE,
                "isolatedfocalterm", controlledVector(1.0f, 0.0f));
        indexControlled(isolated, lexicalCaseId, EvidenceSourceType.CASE_EVIDENCE,
                "kineticamber cobaltpattern kineticamber cobaltpattern kineticamber cobaltpattern",
                controlledVector(0.0f, 1.0f));
        indexControlled(isolated, semanticCaseId, EvidenceSourceType.CASE_EVIDENCE,
                "synthetic orchard telemetry without query vocabulary", controlledVector(1.0f, 0.0f));
        indexControlled(isolated, fusionCaseId, EvidenceSourceType.CASE_EVIDENCE,
                "kineticamber cobaltpattern synthetic fused evidence", controlledVector(0.98f, 0.20f));
        indexControlled(isolated, fusionCaseId, EvidenceSourceType.RESOLUTION,
                "kineticamber cobaltpattern synthetic fused resolution", controlledVector(0.96f, 0.28f));
        indexControlled(isolated, semanticDistractorCaseId, EvidenceSourceType.CASE_EVIDENCE,
                "synthetic semantic window boundary", controlledVector(0.90f, 0.44f));
        for (int distractor = 0; distractor < 6; distractor++) {
            indexControlled(isolated, UUID.randomUUID().toString(), EvidenceSourceType.CASE_EVIDENCE,
                    "synthetic distractor vocabulary " + distractor,
                    controlledVector(0.1f + (distractor * 0.08f), 0.99f - (distractor * 0.05f)));
        }

        Map<String, Object> focalExclusion =
                Map.of("bool", Map.of(
                        "filter", List.of(Map.of("exists", Map.of("field", "caseId"))),
                        "must_not", List.of(Map.of("term", Map.of("caseId", focalCaseId)))));
        List<SearchHit> eligibleHits = rawSearch(isolated, Map.of(
                "size", 100,
                "query", focalExclusion,
                "_source", List.of("sourceId", "caseId")));
        assertEquals(11, eligibleHits.size());
        assertTrue(eligibleHits.size() > knnWindow,
                "the controlled corpus must make k-NN candidate selection material");

        Map<String, Object> bm25Only = Map.of(
                "size", 20,
                "query", Map.of("bool", Map.of(
                        "must", List.of(Map.of("match", Map.of(
                                "text", Map.of("query", queryText, "operator", "or")))),
                        "must_not", List.of(Map.of("term", Map.of("caseId", focalCaseId))))),
                "_source", List.of("sourceId", "caseId"));
        List<SearchHit> lexicalHits = rawSearch(isolated, bm25Only);
        assertEquals(lexicalCaseId, lexicalHits.get(0).caseId(),
                "the lexical-only control must rank the repeated exact terms first");
        assertFalse(lexicalHits.stream().anyMatch(hit -> hit.caseId().equals(semanticCaseId)),
                "the semantic candidate has no meaningful lexical overlap");

        Map<String, Object> knnOnly = Map.of(
                "size", knnWindow,
                "query", Map.of("knn", Map.of("embedding", Map.of(
                        "vector", queryVector,
                        "k", knnWindow,
                        "filter", focalExclusion))),
                "_source", List.of("sourceId", "caseId"));
        List<SearchHit> semanticHits = rawSearch(isolated, knnOnly);
        assertEquals(knnWindow, semanticHits.size(), "k-NN must return only its material candidate window");
        assertEquals(semanticCaseId, semanticHits.get(0).caseId(),
                "the semantic-only candidate must lead the vector control");
        assertFalse(semanticHits.stream().anyMatch(hit -> hit.caseId().equals(lexicalCaseId)),
                "the lexical-only candidate must remain outside the k-NN window");
        assertFalse(semanticHits.stream().anyMatch(hit -> hit.caseId().equals(focalCaseId)),
                "the focal filter must apply to the vector control");

        String pipelineJson = client.get()
                .uri("/_search/pipeline/{pipeline}", isolated.hybridPipeline())
                .retrieve().body(String.class);
        tools.jackson.databind.JsonNode combination = mapper.readTree(pipelineJson)
                .get(isolated.hybridPipeline())
                .get("phase_results_processors").get(0)
                .get("score-ranker-processor").get("combination");
        assertEquals("rrf", combination.get("technique").asText());
        assertEquals(60, combination.get("rank_constant").asInt());

        InvestigationOpenSearchProperties missingPipeline = new InvestigationOpenSearchProperties(
                isolated.url(), isolated.requestTimeout(), isolated.physicalIndex(), isolated.readAlias(),
                isolated.writeAlias(), isolated.hybridPipeline() + "-missing");
        OpenSearchEvidenceRetrievalStore incorrectlyConfiguredStore =
                new OpenSearchEvidenceRetrievalStore(client, mapper, missingPipeline);
        assertThrows(EvidenceStoreUnavailableException.class,
                () -> incorrectlyConfiguredStore.hybridSearch(focalCaseId, queryText, queryVector, knnWindow),
                "the production query must use the configured OpenSearch search pipeline");

        List<EvidenceHit> fusedHits = isolatedRetrievalStore.hybridSearch(
                focalCaseId, queryText, queryVector, knnWindow);
        assertEquals(fusionCaseId, fusedHits.get(0).caseId());
        assertEquals(fusionCaseId, fusedHits.get(1).caseId(),
                "documents ranked in both branches must outrank strong single-branch documents under RRF");
        assertTrue(fusedHits.stream().anyMatch(hit -> hit.caseId().equals(lexicalCaseId)));
        assertTrue(fusedHits.stream().anyMatch(hit -> hit.caseId().equals(semanticCaseId)));
        assertFalse(fusedHits.stream().anyMatch(hit -> hit.caseId().equals(focalCaseId)));

        InvestigationRetrievalService service = new InvestigationRetrievalService(
                isolatedRetrievalStore, ignored -> queryVector.clone(), knnWindow, 2_000, 500);
        InvestigationRetrievalResult grouped = service.retrieve(focalCaseId, queryText, 4);
        assertFalse(grouped.relatedCases().stream().anyMatch(group -> group.caseId().equals(focalCaseId)));
        assertEquals(fusionCaseId, grouped.relatedCases().get(0).caseId());
        assertEquals(2, grouped.relatedCases().get(0).sources().size(),
                "both fused chunks from one related case must occupy one grouped result slot");
        assertTrue(grouped.relatedCases().stream().map(RelatedCaseGroup::caseId).toList()
                .containsAll(List.of(lexicalCaseId, semanticCaseId)));
    }

    @Test
    void hybridSearchOrderingIsDeterministicAcrossRepeatedCalls() {
        String focalCaseId = UUID.randomUUID().toString();
        for (int i = 0; i < 4; i++) {
            String caseId = UUID.randomUUID().toString();
            String text = "synthetic deterministic ordering case number " + i;
            indexStore.index(evidenceDraft(caseId, 0, text), embedding.embed(text));
        }
        String queryText = "synthetic deterministic ordering";
        float[] queryEmbedding = embedding.embed(queryText);

        List<EvidenceHit> first = retrievalStore.hybridSearch(focalCaseId, queryText, queryEmbedding, 10);
        List<EvidenceHit> second = retrievalStore.hybridSearch(focalCaseId, queryText, queryEmbedding, 10);

        Assertions.assertEquals(
                first.stream().map(EvidenceHit::sourceId).toList(),
                second.stream().map(EvidenceHit::sourceId).toList());
    }

    private InvestigationOpenSearchProperties uniqueProperties(String purpose) {
        String suffix = purpose + "-" + UUID.randomUUID();
        String physicalIndex = "evidence-" + suffix;
        return new InvestigationOpenSearchProperties(
                properties.url(), properties.requestTimeout(), physicalIndex,
                physicalIndex + "-read", physicalIndex + "-write", physicalIndex + "-pipeline");
    }

    private String expectedIndexDefinition() {
        try {
            return new String(
                    new ClassPathResource("opensearch/fraud-investigation-evidence-v1.json")
                            .getContentAsByteArray(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot load the evidence index definition", error);
        }
    }

    private void createRawIndex(String index, String definition) {
        client.put().uri("/{index}", index)
                .contentType(MediaType.APPLICATION_JSON)
                .body(definition)
                .retrieve()
                .toBodilessEntity();
    }

    private void addAlias(String index, String alias, boolean writeAlias) {
        Map<String, Object> add = new LinkedHashMap<>();
        add.put("index", index);
        add.put("alias", alias);
        if (writeAlias) {
            add.put("is_write_index", true);
        }
        client.post().uri("/_aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("actions", List.of(Map.of("add", add))))
                .retrieve()
                .toBodilessEntity();
    }

    private Set<String> aliasTargets(String alias) {
        String body = client.get().uri("/_alias/{alias}", alias).retrieve().body(String.class);
        return Set.copyOf(mapper.readTree(body).propertyNames());
    }

    private void indexControlled(
            InvestigationOpenSearchProperties isolated,
            String caseId,
            EvidenceSourceType sourceType,
            String evidenceText,
            float[] controlledEmbedding) {
        String suffix = sourceType == EvidenceSourceType.CASE_EVIDENCE ? "evidence" : "resolution";
        String sourceId = "case:" + caseId + ":" + suffix;
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("sourceId", sourceId);
        document.put("sourceType", sourceType.name());
        document.put("caseId", caseId);
        document.put("text", evidenceText);
        document.put("embedding", controlledEmbedding);
        document.put("projectionVersion", 0L);
        document.put("projectionIntegrity", "controlled-snapshot-" + caseId);
        document.put("chunkState", "ACTIVE");
        if (sourceType == EvidenceSourceType.CASE_EVIDENCE) {
            document.put("publicationComplete", true);
            document.put("resolutionExpected", false);
        }
        client.put().uri("/{alias}/_doc/{id}?refresh=wait_for", isolated.writeAlias(), sourceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(document)
                .retrieve()
                .toBodilessEntity();
        if (sourceType == EvidenceSourceType.CASE_EVIDENCE) {
            String resolutionId = "case:" + caseId + ":resolution";
            client.put().uri("/{alias}/_doc/{id}?refresh=wait_for", isolated.writeAlias(), resolutionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "sourceId", resolutionId,
                            "projectionVersion", 0L,
                            "projectionIntegrity", "controlled-snapshot-" + caseId,
                            "chunkState", "ABSENT"))
                    .retrieve()
                    .toBodilessEntity();
        } else {
            String evidenceId = "case:" + caseId + ":evidence";
            client.post().uri("/{alias}/_update/{id}?refresh=wait_for", isolated.writeAlias(), evidenceId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("doc", Map.of("resolutionExpected", true)))
                    .retrieve()
                    .toBodilessEntity();
        }
    }

    private float[] controlledVector(float first, float second) {
        float[] vector = new float[DIMENSIONS];
        vector[0] = first;
        vector[1] = second;
        return vector;
    }

    private List<SearchHit> rawSearch(
            InvestigationOpenSearchProperties isolated, Map<String, Object> searchBody) {
        String body = client.post().uri("/{alias}/_search", isolated.readAlias())
                .contentType(MediaType.APPLICATION_JSON)
                .body(searchBody)
                .retrieve()
                .body(String.class);
        tools.jackson.databind.JsonNode hits = mapper.readTree(body).get("hits").get("hits");
        List<SearchHit> results = new ArrayList<>(hits.size());
        for (int index = 0; index < hits.size(); index++) {
            tools.jackson.databind.JsonNode source = hits.get(index).get("_source");
            results.add(new SearchHit(source.get("sourceId").asText(), source.get("caseId").asText()));
        }
        return results;
    }

    private record SearchHit(String sourceId, String caseId) {
    }
}
