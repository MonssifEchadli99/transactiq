package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dockerjava.api.DockerClient;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EmbeddingPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EvidenceIndexPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceDraft;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceSourceType;
import io.github.monssifechadli99.transactiq.investigation_assistant.support.DeterministicEmbeddingAdapter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.listener.auto-startup=false",
        "investigation-assistant.opensearch.physical-index=investigation-api-it-v1",
        "investigation-assistant.opensearch.read-alias=investigation-api-it-read",
        "investigation-assistant.opensearch.write-alias=investigation-api-it-write",
        "investigation-assistant.opensearch.request-timeout=5s",
        "spring.ai.openai.api-key=test-key-not-a-real-secret"
})
@Import(InvestigationRetrievalApiIntegrationTest.FakeEmbeddingConfiguration.class)
class InvestigationRetrievalApiIntegrationTest {

    @Container
    static final GenericContainer<?> openSearch =
            new GenericContainer<>(DockerImageName.parse("opensearchproject/opensearch:3.2.0"))
                    .withEnv("discovery.type", "single-node")
                    .withEnv("DISABLE_SECURITY_PLUGIN", "true")
                    .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
                    .withExposedPorts(9200);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("investigation-assistant.opensearch.url",
                () -> "http://" + openSearch.getHost() + ":" + openSearch.getMappedPort(9200));
    }

    @TestConfiguration
    static class FakeEmbeddingConfiguration {
        @Bean
        @Primary
        EmbeddingPort fakeEmbeddingPort() {
            return new DeterministicEmbeddingAdapter(1536);
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    EvidenceIndexPort indexPort;

    @Autowired
    EmbeddingPort embeddingPort;

    private RestClient api;

    @BeforeEach
    void setUp() {
        api = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private void index(EvidenceDraft draft) {
        indexPort.index(draft, embeddingPort.embed(draft.text()));
    }

    @Test
    void returnsFocalAndRelatedSourcesWithNoProhibitedFields() {
        String focalCaseId = UUID.randomUUID().toString();
        String relatedCaseId = UUID.randomUUID().toString();
        String sharedVocabulary = "widgetcorp velocity abuse pattern synthetic evidence";
        index(new EvidenceDraft("case:" + focalCaseId + ":evidence", EvidenceSourceType.CASE_EVIDENCE,
                focalCaseId, sharedVocabulary, 0));
        index(new EvidenceDraft("case:" + relatedCaseId + ":evidence", EvidenceSourceType.CASE_EVIDENCE,
                relatedCaseId, sharedVocabulary + " repeated at another merchant", 0));

        String body = api.post()
                .uri("/api/v1/fraud-cases/{caseId}/investigation/retrieval", focalCaseId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"question\":\"Why is this transaction suspicious?\",\"maxRelatedCases\":5}")
                .retrieve().body(String.class);

        assertTrue(body.contains("\"caseId\":\"" + focalCaseId + "\""), body);
        assertTrue(body.contains("\"focalSources\""), body);
        assertTrue(body.contains("\"relatedCases\""), body);
        assertTrue(body.contains("\"sourceType\":\"CASE_EVIDENCE\""), body);
        assertFalse(body.contains("\"vector\""), body);
        assertFalse(body.contains("\"score\""), body);
        assertFalse(body.contains("projectionVersion"), body);
        assertFalse(body.contains("embedding"), body);
        assertTrue(body.contains(relatedCaseId), "related case must surface via hybrid search");
    }

    @Test
    void returns404WhenFocalEvidenceIsNotYetIndexed() {
        HttpClientErrorException.NotFound error = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientErrorException.NotFound.class,
                () -> api.post()
                        .uri("/api/v1/fraud-cases/{caseId}/investigation/retrieval", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"question\":\"why?\"}")
                        .retrieve().toBodilessEntity());
        assertTrue(error.getResponseBodyAsString().contains("FOCAL_EVIDENCE_NOT_FOUND"));
    }

    @Test
    void returns400ForInvalidCaseIdQuestionAndLimit() {
        assertBadRequest(() -> api.post()
                .uri("/api/v1/fraud-cases/{caseId}/investigation/retrieval", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"question\":\"why?\"}")
                .retrieve().toBodilessEntity());

        assertBadRequest(() -> api.post()
                .uri("/api/v1/fraud-cases/{caseId}/investigation/retrieval", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"question\":\"   \"}")
                .retrieve().toBodilessEntity());

        assertBadRequest(() -> api.post()
                .uri("/api/v1/fraud-cases/{caseId}/investigation/retrieval", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"question\":\"why?\",\"maxRelatedCases\":11}")
                .retrieve().toBodilessEntity());
    }

    @Test
    void returns503WhenOpenSearchIsUnavailable() {
        String caseId = UUID.randomUUID().toString();
        DockerClient docker = openSearch.getDockerClient();
        docker.pauseContainerCmd(openSearch.getContainerId()).exec();
        try {
            HttpServerErrorException.ServiceUnavailable error = org.junit.jupiter.api.Assertions.assertThrows(
                    HttpServerErrorException.ServiceUnavailable.class,
                    () -> api.post()
                            .uri("/api/v1/fraud-cases/{caseId}/investigation/retrieval", caseId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"question\":\"why?\"}")
                            .retrieve().toBodilessEntity());
            assertTrue(error.getResponseBodyAsString().contains("INVESTIGATION_RETRIEVAL_UNAVAILABLE"));
        } finally {
            docker.unpauseContainerCmd(openSearch.getContainerId()).exec();
        }
    }

    private void assertBadRequest(Runnable call) {
        HttpClientErrorException.BadRequest error =
                org.junit.jupiter.api.Assertions.assertThrows(HttpClientErrorException.BadRequest.class, call::run);
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }
}
