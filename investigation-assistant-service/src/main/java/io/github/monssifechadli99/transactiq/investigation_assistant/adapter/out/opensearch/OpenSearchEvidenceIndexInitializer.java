package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.out.opensearch;

import io.github.monssifechadli99.transactiq.investigation_assistant.configuration.InvestigationOpenSearchProperties;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotently provisions the dedicated evidence index, its read/write aliases, and the
 * RRF hybrid search pipeline on startup. Never deletes or recreates an existing index;
 * an incompatible pre-existing mapping fails fast instead.
 */
public final class OpenSearchEvidenceIndexInitializer implements InitializingBean {

    private final RestClient client;
    private final InvestigationOpenSearchProperties properties;
    private final ObjectMapper mapper;

    public OpenSearchEvidenceIndexInitializer(
            RestClient client, InvestigationOpenSearchProperties properties, ObjectMapper mapper) {
        this.client = client;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public void afterPropertiesSet() {
        String mapping = readClasspathResource("opensearch/fraud-investigation-evidence-v1.json");
        createIndex(mapping);
        verifyKnnEnabled();
        verifyMapping(mapping);
        reconcileAliases();
        provisionHybridPipeline();
    }

    private void createIndex(String mapping) {
        try {
            client.put().uri("/{index}", properties.physicalIndex())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapping)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.BadRequest error) {
            if (!error.getResponseBodyAsString().contains("resource_already_exists_exception")) {
                throw error;
            }
        }
    }

    private void verifyKnnEnabled() {
        String body = client.get()
                .uri("/{index}/_settings?flat_settings=true&include_defaults=true", properties.physicalIndex())
                .retrieve()
                .body(String.class);
        JsonNode root = mapper.readTree(body == null ? "{}" : body);
        JsonNode indexNode = root.get(properties.physicalIndex());
        JsonNode settings = indexNode == null ? null : indexNode.get("settings");
        JsonNode knn = settings == null ? null : settings.get("index.knn");
        if (knn == null || !knn.asBoolean()) {
            throw new IllegalArgumentException(
                    "Existing OpenSearch index is incompatible because vector search is not enabled");
        }
    }

    private void reconcileAliases() {
        boolean readPresent = verifyAliasIfPresent(properties.readAlias(), false);
        boolean writePresent = verifyAliasIfPresent(properties.writeAlias(), true);

        List<String> missingAliases = new ArrayList<>(2);
        if (!readPresent) {
            missingAliases.add("{\"add\":{\"index\":\"%s\",\"alias\":\"%s\"}}"
                    .formatted(properties.physicalIndex(), properties.readAlias()));
        }
        if (!writePresent) {
            missingAliases.add("{\"add\":{\"index\":\"%s\",\"alias\":\"%s\",\"is_write_index\":true}}"
                    .formatted(properties.physicalIndex(), properties.writeAlias()));
        }
        if (missingAliases.isEmpty()) {
            return;
        }

        String aliases = "{\"actions\":[" + String.join(",", missingAliases) + "]}";
        client.post().uri("/_aliases").contentType(MediaType.APPLICATION_JSON)
                .body(aliases).retrieve().toBodilessEntity();
        requireAlias(properties.readAlias(), false);
        requireAlias(properties.writeAlias(), true);
    }

    private void requireAlias(String alias, boolean expectedWriteAlias) {
        if (!verifyAliasIfPresent(alias, expectedWriteAlias)) {
            throw new IllegalStateException("Required Fraud Investigation Evidence alias is missing");
        }
    }

    private void verifyMapping(String expectedJson) {
        JsonNode expected = mapper.readTree(expectedJson).get("mappings");
        String body = client.get().uri("/{index}/_mapping", properties.physicalIndex())
                .retrieve().body(String.class);
        JsonNode root = mapper.readTree(body == null ? "{}" : body);
        JsonNode indexNode = root.get(properties.physicalIndex());
        JsonNode actual = indexNode == null ? null : indexNode.get("mappings");
        if (!equivalent(actual, expected)) {
            throw new IllegalArgumentException(
                    "Existing OpenSearch index mapping is incompatible with the "
                            + "Fraud Investigation Evidence v1 mapping");
        }
    }

    private boolean verifyAliasIfPresent(String alias, boolean expectedWriteAlias) {
        String body;
        try {
            body = client.get()
                    .uri("/_alias/{alias}", alias)
                    .retrieve().body(String.class);
        } catch (HttpClientErrorException.NotFound error) {
            return false;
        }
        JsonNode indices = mapper.readTree(body == null ? "{}" : body);
        if (indices.size() != 1 || indices.get(properties.physicalIndex()) == null) {
            throw new IllegalArgumentException("Fraud Investigation Evidence alias has an unexpected index target");
        }
        JsonNode aliases = indices.get(properties.physicalIndex()).get("aliases");
        JsonNode aliasDefinition = aliases == null ? null : aliases.get(alias);
        if (aliasDefinition == null) {
            throw new IllegalArgumentException("Fraud Investigation Evidence alias definition is incompatible");
        }
        JsonNode writeFlag = aliasDefinition.get("is_write_index");
        if (expectedWriteAlias && (writeFlag == null || !writeFlag.asBoolean())) {
            throw new IllegalArgumentException("Fraud Investigation Evidence write alias is not the designated write index");
        }
        if (!expectedWriteAlias && writeFlag != null && writeFlag.asBoolean()) {
            throw new IllegalArgumentException("Fraud Investigation Evidence read alias is unexpectedly write-enabled");
        }
        return true;
    }

    private void provisionHybridPipeline() {
        String pipeline = """
                {"description":"Fraud investigation evidence hybrid BM25 + k-NN RRF pipeline",
                "phase_results_processors":[{"score-ranker-processor":{"combination":{"technique":"rrf","rank_constant":60}}}]}""";
        client.put().uri("/_search/pipeline/{id}", properties.hybridPipeline())
                .contentType(MediaType.APPLICATION_JSON)
                .body(pipeline)
                .retrieve()
                .toBodilessEntity();
    }

    private static String readClasspathResource(String path) {
        try {
            return new String(new ClassPathResource(path).getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot read classpath resource " + path, error);
        }
    }

    private static boolean equivalent(JsonNode actual, JsonNode expected) {
        if (actual == null || expected == null) {
            return actual == expected;
        }
        if (actual.isNumber() && expected.isNumber()) {
            return actual.decimalValue().compareTo(expected.decimalValue()) == 0;
        }
        if (actual.isObject() && expected.isObject()) {
            Set<String> actualNames = new HashSet<>(actual.propertyNames());
            Set<String> expectedNames = new HashSet<>(expected.propertyNames());
            if (!actualNames.equals(expectedNames)) {
                return false;
            }
            for (String name : actualNames) {
                if (!equivalent(actual.get(name), expected.get(name))) {
                    return false;
                }
            }
            return true;
        }
        if (actual.isArray() && expected.isArray()) {
            if (actual.size() != expected.size()) {
                return false;
            }
            for (int i = 0; i < actual.size(); i++) {
                if (!equivalent(actual.get(i), expected.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return actual.equals(expected);
    }
}
