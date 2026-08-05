package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.out.opensearch;

import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EvidenceRetrievalPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EvidenceStoreUnavailableException;
import io.github.monssifechadli99.transactiq.investigation_assistant.configuration.InvestigationOpenSearchProperties;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceHit;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceSourceType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Read path for investigation retrieval. OpenSearch performs BM25 + k-NN RRF ranking,
 * while this adapter enforces the private publication barrier before any hit reaches
 * application code: case evidence and the resolution slot must form one complete snapshot.
 */
public final class OpenSearchEvidenceRetrievalStore implements EvidenceRetrievalPort {

    private static final String ACTIVE = "ACTIVE";
    private static final String ABSENT = "ABSENT";
    private static final List<String> SEARCH_SOURCE_FIELDS = List.of(
            "sourceId", "sourceType", "caseId", "text",
            "projectionVersion", "projectionIntegrity", "chunkState",
            "publicationComplete", "resolutionExpected");
    private static final List<String> SNAPSHOT_SOURCE_FIELDS = SEARCH_SOURCE_FIELDS;

    private final RestClient client;
    private final ObjectMapper mapper;
    private final InvestigationOpenSearchProperties properties;

    public OpenSearchEvidenceRetrievalStore(
            RestClient client, ObjectMapper mapper, InvestigationOpenSearchProperties properties) {
        this.client = client;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public List<EvidenceHit> loadFocal(String caseId) {
        SnapshotGeneration generation = loadGenerations(Set.of(caseId)).get(caseId);
        if (generation == null) {
            return List.of();
        }
        if (!generation.complete()) {
            throw unavailable();
        }
        return generation.publicHits();
    }

    @Override
    public List<EvidenceHit> hybridSearch(
            String excludeCaseId, String retrievalText, float[] retrievalEmbedding, int candidatePoolSize) {
        Map<String, Object> hybrid = new LinkedHashMap<>();
        hybrid.put("filter", Map.of("bool", Map.of(
                "must_not", List.of(Map.of("term", Map.of("caseId", excludeCaseId))))));
        hybrid.put("queries", List.of(
                Map.of("match", Map.of("text", Map.of("query", retrievalText, "operator", "or"))),
                Map.of("knn", Map.of("embedding", Map.of("vector", retrievalEmbedding, "k", candidatePoolSize)))));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", candidatePoolSize);
        body.put("query", Map.of("hybrid", hybrid));
        body.put("_source", SEARCH_SOURCE_FIELDS);

        List<ScoredHit> hits = new ArrayList<>(search(
                "/{alias}/_search?search_pipeline={pipeline}",
                new Object[] {properties.readAlias(), properties.hybridPipeline()}, body));
        hits.sort(Comparator.comparingDouble(ScoredHit::score).reversed()
                .thenComparing(hit -> hit.hit().sourceId()));
        if (hits.isEmpty()) {
            return List.of();
        }

        Set<String> caseIds = new LinkedHashSet<>();
        for (ScoredHit hit : hits) {
            caseIds.add(hit.hit().caseId());
        }
        Map<String, SnapshotGeneration> generations = loadGenerations(caseIds);
        return hits.stream()
                .filter(hit -> {
                    SnapshotGeneration generation = generations.get(hit.hit().caseId());
                    return generation != null && generation.complete() && generation.includes(hit);
                })
                .map(ScoredHit::hit)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<ScoredHit> search(String uriTemplate, Object[] uriArgs, Map<String, Object> body) {
        try {
            String json = client.post().uri(uriTemplate, uriArgs)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(LogSafeJsonBody.of(mapper, body))
                    .retrieve()
                    .body(String.class);
            Map<String, Object> root = mapper.readValue(json, Map.class);
            Map<String, Object> hitsRoot = (Map<String, Object>) root.get("hits");
            List<Map<String, Object>> hits = (List<Map<String, Object>>) hitsRoot.get("hits");
            List<ScoredHit> results = new ArrayList<>();
            for (Map<String, Object> hit : hits) {
                ScoredHit parsed = toScoredHit(hit);
                if (parsed != null) {
                    results.add(parsed);
                }
            }
            return results;
        } catch (RuntimeException error) {
            throw unavailable();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, SnapshotGeneration> loadGenerations(Set<String> caseIds) {
        if (caseIds.isEmpty()) {
            return Map.of();
        }
        try {
            List<Map<String, Object>> requestedDocuments = new ArrayList<>(caseIds.size() * 2);
            for (String caseId : caseIds) {
                requestedDocuments.add(Map.of("_id", evidenceId(caseId), "_source", SNAPSHOT_SOURCE_FIELDS));
                requestedDocuments.add(Map.of("_id", resolutionId(caseId), "_source", SNAPSHOT_SOURCE_FIELDS));
            }
            String json = client.post().uri("/{alias}/_mget", properties.readAlias())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(LogSafeJsonBody.of(mapper, Map.of("docs", requestedDocuments)))
                    .retrieve()
                    .body(String.class);
            Map<String, Object> root = mapper.readValue(json, Map.class);
            List<Map<String, Object>> documents = (List<Map<String, Object>>) root.get("docs");
            Map<String, StoredDocument> byId = new LinkedHashMap<>();
            Set<String> expectedIds = new LinkedHashSet<>();
            for (String caseId : caseIds) {
                expectedIds.add(evidenceId(caseId));
                expectedIds.add(resolutionId(caseId));
            }
            Set<String> seenIds = new LinkedHashSet<>();
            for (Map<String, Object> document : documents) {
                if (!(document.get("_id") instanceof String id)
                        || !expectedIds.contains(id)
                        || !seenIds.add(id)
                        || document.containsKey("error")
                        || !(document.get("found") instanceof Boolean found)) {
                    throw unavailable();
                }
                if (!found) {
                    continue;
                }
                if (!(document.get("_source") instanceof Map<?, ?> rawSource)) {
                    throw unavailable();
                }
                byId.put(id, StoredDocument.from((Map<String, Object>) rawSource));
            }
            if (!seenIds.equals(expectedIds)) {
                throw unavailable();
            }

            Map<String, SnapshotGeneration> generations = new LinkedHashMap<>();
            for (String caseId : caseIds) {
                StoredDocument evidence = byId.get(evidenceId(caseId));
                StoredDocument resolution = byId.get(resolutionId(caseId));
                if (evidence != null || resolution != null) {
                    generations.put(caseId, new SnapshotGeneration(caseId, evidence, resolution));
                }
            }
            return generations;
        } catch (RuntimeException error) {
            throw unavailable();
        }
    }

    @SuppressWarnings("unchecked")
    private static ScoredHit toScoredHit(Map<String, Object> hit) {
        if (!(hit.get("_source") instanceof Map<?, ?> rawSource)) {
            return null;
        }
        StoredDocument document = StoredDocument.from((Map<String, Object>) rawSource);
        EvidenceHit evidenceHit = document.activeHit();
        if (evidenceHit == null || document.version() == null || document.integrity() == null) {
            return null;
        }
        Object rawScore = hit.get("_score");
        double score = rawScore instanceof Number number ? number.doubleValue() : 0.0;
        return new ScoredHit(evidenceHit, score, document.version(), document.integrity(), document.state());
    }

    private static EvidenceStoreUnavailableException unavailable() {
        return new EvidenceStoreUnavailableException("Fraud investigation evidence search is unavailable");
    }

    private static String evidenceId(String caseId) {
        return "case:" + caseId + ":evidence";
    }

    private static String resolutionId(String caseId) {
        return "case:" + caseId + ":resolution";
    }

    private record ScoredHit(EvidenceHit hit, double score, Long version, String integrity, String state) {
        @Override
        public String toString() {
            return "ScoredHit[content=<redacted>]";
        }
    }

    private record StoredDocument(
            String sourceId,
            String sourceType,
            String caseId,
            String text,
            Long version,
            String integrity,
            String state,
            Boolean publicationComplete,
            Boolean resolutionExpected) {

        static StoredDocument from(Map<String, Object> source) {
            return new StoredDocument(
                    string(source.get("sourceId")),
                    string(source.get("sourceType")),
                    string(source.get("caseId")),
                    string(source.get("text")),
                    source.get("projectionVersion") instanceof Number number ? number.longValue() : null,
                    string(source.get("projectionIntegrity")),
                    string(source.get("chunkState")),
                    source.get("publicationComplete") instanceof Boolean value ? value : null,
                    source.get("resolutionExpected") instanceof Boolean value ? value : null);
        }

        EvidenceHit activeHit() {
            if (!ACTIVE.equals(state) || sourceId == null || sourceType == null || caseId == null || text == null) {
                return null;
            }
            try {
                return new EvidenceHit(sourceId, EvidenceSourceType.valueOf(sourceType), caseId, text);
            } catch (IllegalArgumentException invalidType) {
                return null;
            }
        }

        private static String string(Object value) {
            return value instanceof String textValue ? textValue : null;
        }

        @Override
        public String toString() {
            return "StoredDocument[content=<redacted>]";
        }
    }

    private record SnapshotGeneration(String caseId, StoredDocument evidence, StoredDocument resolution) {

        boolean complete() {
            if (evidence == null || resolution == null
                    || evidence.version() == null || evidence.version() < 0
                    || evidence.integrity() == null || evidence.integrity().isBlank()
                    || !Boolean.TRUE.equals(evidence.publicationComplete())
                    || evidence.resolutionExpected() == null
                    || !sameSnapshot(evidence, resolution)
                    || !validActive(evidence, evidenceId(caseId), EvidenceSourceType.CASE_EVIDENCE)) {
                return false;
            }
            return evidence.resolutionExpected()
                    ? validActive(resolution, resolutionId(caseId), EvidenceSourceType.RESOLUTION)
                    : validAbsent(resolution, resolutionId(caseId));
        }

        List<EvidenceHit> publicHits() {
            if (!complete()) {
                return List.of();
            }
            EvidenceHit evidenceHit = evidence.activeHit();
            return evidence.resolutionExpected()
                    ? List.of(evidenceHit, resolution.activeHit())
                    : List.of(evidenceHit);
        }

        boolean includes(ScoredHit hit) {
            StoredDocument expected = hit.hit().sourceId().equals(evidenceId(caseId)) ? evidence
                    : hit.hit().sourceId().equals(resolutionId(caseId)) ? resolution : null;
            return expected != null
                    && ACTIVE.equals(expected.state())
                    && expected.version().equals(hit.version())
                    && expected.integrity().equals(hit.integrity())
                    && expected.state().equals(hit.state());
        }

        private static boolean sameSnapshot(StoredDocument first, StoredDocument second) {
            return first.version().equals(second.version())
                    && first.integrity().equals(second.integrity());
        }

        private boolean validActive(
                StoredDocument document, String expectedId, EvidenceSourceType expectedType) {
            EvidenceHit hit = document.activeHit();
            return hit != null
                    && expectedId.equals(hit.sourceId())
                    && expectedType == hit.sourceType()
                    && caseId.equals(hit.caseId());
        }

        private static boolean validAbsent(StoredDocument document, String expectedId) {
            return expectedId.equals(document.sourceId())
                    && ABSENT.equals(document.state())
                    && document.sourceType() == null
                    && document.caseId() == null
                    && document.text() == null
                    && document.publicationComplete() == null
                    && document.resolutionExpected() == null;
        }

        @Override
        public String toString() {
            return "SnapshotGeneration[caseId=" + caseId + ", content=<redacted>]";
        }
    }
}
