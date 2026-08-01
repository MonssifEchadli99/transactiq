package io.github.monssifechadli99.transactiq.case_management.adapter.in.web;

import static io.github.monssifechadli99.transactiq.case_management.support.AuthorizationEventFixtures.reviewEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka.AuthorizationCompletedEventParser;
import io.github.monssifechadli99.transactiq.case_management.application.port.out.FraudCaseStore;
import io.github.monssifechadli99.transactiq.case_management.support.PostgreSqlTestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.listener.auto-startup=false")
@Import(PostgreSqlTestcontainersConfiguration.class)
class FraudCaseHttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired FraudCaseStore caseStore;

    private final AuthorizationCompletedEventParser parser = new AuthorizationCompletedEventParser();
    private RestClient client;
    private UUID caseId;

    @BeforeEach
    void setup() {
        jdbc.sql("TRUNCATE fraud_case.fraud_cases CASCADE").update();
        UUID requestId = UUID.randomUUID();
        caseStore.create(parser.parse(reviewEvent(UUID.randomUUID(), requestId).build().toByteArray()));
        caseId = jdbc.sql("SELECT case_id FROM fraud_case.fraud_cases WHERE request_id = :requestId")
                .param("requestId", requestId).query(UUID.class).single();
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void queueDetailsAndClaimExposeApprovedContract() {
        String queue = get("/api/v1/fraud-cases?pageSize=1");
        assertTrue(queue.contains("\"caseId\":\"" + caseId + "\""), queue);
        assertTrue(queue.contains("\"status\":\"NEW\""), queue);
        assertTrue(queue.contains("\"version\":0"), queue);

        String details = get("/api/v1/fraud-cases/" + caseId);
        assertTrue(details.contains("\"cardTokenFingerprint\""), details);
        assertTrue(details.contains("\"matchedRules\":[{\"matchOrder\":0"), details);
        assertFalse(details.contains("cardToken\""), details);
        assertFalse(details.contains("tok_"), details);

        String claimed = client.post().uri("/api/v1/fraud-cases/{id}/claim", caseId)
                .header(FraudCaseController.ANALYST_HEADER, "Analyst-A")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"expectedVersion\":0}")
                .retrieve().body(String.class);
        assertTrue(claimed.contains("\"status\":\"IN_REVIEW\""), claimed);
        assertTrue(claimed.contains("\"assigneeId\":\"Analyst-A\""), claimed);
        assertTrue(claimed.contains("\"version\":1"), claimed);

        String mine = getWithHeader("/api/v1/fraud-cases?assignment=MINE", "Analyst-A");
        assertTrue(mine.contains(caseId.toString()), mine);
    }

    @Test
    void mapsNotFoundConflictAndInvalidInputsPrecisely() {
        assertError(HttpStatus.NOT_FOUND, "FRAUD_CASE_NOT_FOUND",
                () -> get("/api/v1/fraud-cases/" + UUID.randomUUID()));
        assertError(HttpStatus.BAD_REQUEST, "INVALID_PAGE_SIZE",
                () -> get("/api/v1/fraud-cases?pageSize=0"));
        assertError(HttpStatus.BAD_REQUEST, "INVALID_CURSOR",
                () -> get("/api/v1/fraud-cases?cursor=invalid"));
        assertError(HttpStatus.BAD_REQUEST, "INVALID_FRAUD_CASE_REQUEST",
                () -> get("/api/v1/fraud-cases?status=UNKNOWN"));
        assertError(HttpStatus.BAD_REQUEST, "INVALID_ANALYST_ID",
                () -> get("/api/v1/fraud-cases?assignment=MINE"));
        assertError(HttpStatus.BAD_REQUEST, "INVALID_ANALYST_ID",
                () -> postClaim(caseId, null, "{\"expectedVersion\":0}"));
        assertError(HttpStatus.BAD_REQUEST, "INVALID_FRAUD_CASE_REQUEST",
                () -> postClaim(caseId, "analyst-a", "{\"expectedVersion\":-1}"));
        assertError(HttpStatus.BAD_REQUEST, "INVALID_FRAUD_CASE_REQUEST",
                () -> postClaim(caseId, "analyst-a", "{}"));

        postClaim(caseId, "analyst-a", "{\"expectedVersion\":0}");
        assertError(HttpStatus.CONFLICT, "CASE_ALREADY_ASSIGNED",
                () -> postClaim(caseId, "analyst-b", "{\"expectedVersion\":0}"));
    }

    @Test
    void resolveReturnsCompleteCaseSupportsExactRetryAndHistory() {
        postClaim(caseId, "analyst-a", "{\"expectedVersion\":0}");
        String body = """
                {"expectedVersion":1,"outcome":"CONFIRMED_FRAUD",
                 "rationale":"  Synthetic evidence confirms fraud.  "}
                """;
        String resolved = postResolve(caseId, "analyst-a", body);
        assertTrue(resolved.contains("\"status\":\"RESOLVED\""), resolved);
        assertTrue(resolved.contains("\"assigneeId\":\"analyst-a\""), resolved);
        assertTrue(resolved.contains("\"version\":2"), resolved);
        assertTrue(resolved.contains("\"resolutionOutcome\":\"CONFIRMED_FRAUD\""), resolved);
        assertTrue(resolved.contains("\"resolutionRationale\":\"Synthetic evidence confirms fraud.\""), resolved);
        assertTrue(resolved.contains("\"resolvedBy\":\"analyst-a\""), resolved);
        assertTrue(resolved.contains("\"matchedRules\""), resolved);

        assertEquals(resolved, postResolve(caseId, " analyst-a ", body));
        String history = get("/api/v1/fraud-cases/" + caseId + "/history");
        assertTrue(history.indexOf("\"eventType\":\"CLAIMED\"")
                < history.indexOf("\"eventType\":\"RESOLVED\""), history);
        assertTrue(history.contains("\"caseVersion\":1"), history);
        assertTrue(history.contains("\"caseVersion\":2"), history);
        assertTrue(history.contains("\"resolutionOutcome\":null"), history);
    }

    @Test
    void resolveAndHistoryMapApprovedValidationAndConflicts() {
        assertError(HttpStatus.NOT_FOUND, "FRAUD_CASE_NOT_FOUND",
                () -> get("/api/v1/fraud-cases/" + UUID.randomUUID() + "/history"));
        assertEquals("{\"items\":[]}", get("/api/v1/fraud-cases/" + caseId + "/history"));
        assertError(HttpStatus.CONFLICT, "CASE_NOT_IN_REVIEW",
                () -> postResolve(caseId, "analyst-a", validResolution(0)));

        postClaim(caseId, "analyst-a", "{\"expectedVersion\":0}");
        assertError(HttpStatus.CONFLICT, "CASE_NOT_ASSIGNED_TO_ANALYST",
                () -> postResolve(caseId, "analyst-b", validResolution(1)));
        assertError(HttpStatus.CONFLICT, "CASE_VERSION_CONFLICT",
                () -> postResolve(caseId, "analyst-a", validResolution(0)));
        assertError(HttpStatus.BAD_REQUEST, "INVALID_ANALYST_ID",
                () -> postResolve(caseId, null, validResolution(1)));
        assertError(HttpStatus.BAD_REQUEST, "INVALID_RESOLUTION_RATIONALE",
                () -> postResolve(caseId, "analyst-a",
                        "{\"expectedVersion\":1,\"outcome\":\"CONFIRMED_FRAUD\",\"rationale\":\"short\"}"));
        assertError(HttpStatus.BAD_REQUEST, "INVALID_FRAUD_CASE_REQUEST",
                () -> postResolve(caseId, "analyst-a",
                        "{\"outcome\":\"CONFIRMED_FRAUD\",\"rationale\":\"Synthetic rationale\"}"));
        assertError(HttpStatus.BAD_REQUEST, "INVALID_FRAUD_CASE_REQUEST",
                () -> postResolve(caseId, "analyst-a",
                        "{\"expectedVersion\":1,\"rationale\":\"Synthetic rationale\"}"));

        postResolve(caseId, "analyst-a", validResolution(1));
        assertError(HttpStatus.CONFLICT, "CASE_ALREADY_RESOLVED_DIFFERENTLY",
                () -> postResolve(caseId, "analyst-a", """
                        {"expectedVersion":1,"outcome":"FALSE_POSITIVE",
                         "rationale":"Synthetic rationale"}
                        """));
        assertError(HttpStatus.CONFLICT, "CASE_VERSION_CONFLICT",
                () -> postResolve(caseId, "analyst-a", validResolution(0)));
    }

    private String get(String uri) {
        return client.get().uri(uri).retrieve().body(String.class);
    }

    private String getWithHeader(String uri, String analyst) {
        return client.get().uri(uri).header(FraudCaseController.ANALYST_HEADER, analyst)
                .retrieve().body(String.class);
    }

    private String postClaim(UUID id, String analyst, String body) {
        var request = client.post().uri("/api/v1/fraud-cases/{id}/claim", id)
                .contentType(MediaType.APPLICATION_JSON);
        if (analyst != null) {
            request = request.header(FraudCaseController.ANALYST_HEADER, analyst);
        }
        return request.body(body).retrieve().body(String.class);
    }

    private String postResolve(UUID id, String analyst, String body) {
        var request = client.post().uri("/api/v1/fraud-cases/{id}/resolve", id)
                .contentType(MediaType.APPLICATION_JSON);
        if (analyst != null) {
            request = request.header(FraudCaseController.ANALYST_HEADER, analyst);
        }
        return request.body(body).retrieve().body(String.class);
    }

    private static String validResolution(long version) {
        return "{\"expectedVersion\":" + version
                + ",\"outcome\":\"CONFIRMED_FRAUD\","
                + "\"rationale\":\"Synthetic rationale\"}";
    }

    private static void assertError(HttpStatus status, String code, ThrowingCall call) {
        HttpClientErrorException exception = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientErrorException.class, call::run);
        assertEquals(status, exception.getStatusCode());
        assertTrue(exception.getResponseBodyAsString().contains("\"code\":\"" + code + "\""),
                exception.getResponseBodyAsString());
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
