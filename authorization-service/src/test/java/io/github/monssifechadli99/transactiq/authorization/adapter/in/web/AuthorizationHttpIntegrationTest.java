package io.github.monssifechadli99.transactiq.authorization.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.authorization.support.AuthorizationServiceIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

@AuthorizationServiceIntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AuthorizationHttpIntegrationTest {

    private static final List<UUID> REQUEST_IDS = List.of(
            UUID.fromString("96f772f7-c083-4392-b9c6-443d83864675"),
            UUID.fromString("b0b2f16b-7a49-4491-8760-d65017fd7f22"),
            UUID.fromString("ff78b3eb-3696-4b66-aac2-d24e32296118"),
            UUID.fromString("a9e55f35-3be6-42b7-a9e9-73df8fb91813"),
            UUID.fromString("c6373029-ed4a-4970-815b-bb1a7149500f"));

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbcClient;

    private RestClient restClient;

    @BeforeEach
    void createRestClient() {
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @AfterEach
    void removeAuthorizationTestData() {
        jdbcClient.sql(
                        """
                        DELETE FROM "authorization".balance_reservations
                        WHERE request_id IN (:requestIds)
                        """)
                .param("requestIds", REQUEST_IDS)
                .update();
        jdbcClient.sql(
                        """
                        DELETE FROM "authorization".authorization_ledger
                        WHERE request_id IN (:requestIds)
                        """)
                .param("requestIds", REQUEST_IDS)
                .update();
        jdbcClient.sql(
                        """
                        DELETE FROM "authorization".authorization_requests
                        WHERE request_id IN (:requestIds)
                        """)
                .param("requestIds", REQUEST_IDS)
                .update();
        jdbcClient.sql(
                        """
                        UPDATE "authorization".card_accounts
                        SET reserved_amount = 0.00
                        WHERE card_token = 'tok_A1B2C3D4'
                        """)
                .update();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("successfulBusinessOutcomes")
    void returnsSuccessfulBusinessOutcome(Scenario scenario) {
        ResponseEntity<String> response = restClient.post()
                .uri("/api/v1/authorizations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestJson(scenario))
                .retrieve()
                .toEntity(String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = response.getBody();
        assertNotNull(body);
        assertTrue(
                body.contains("\"requestId\":\"" + scenario.requestId() + "\""),
                body);
        assertTrue(
                body.contains("\"decision\":\"" + scenario.decision() + "\""),
                body);
        if (scenario.declineReason() == null) {
            assertFalse(body.contains("\"declineReason\""), body);
        } else {
            assertTrue(
                    body.contains("\"declineReason\":\"" + scenario.declineReason() + "\""),
                    body);
        }
        assertFalse(body.contains("fraudAssessment"), body);
        assertFalse(body.contains("fraudCaseRequired"), body);
    }

    private static Stream<Scenario> successfulBusinessOutcomes() {
        return Stream.of(
                new Scenario(
                        "normal request is approved",
                        UUID.fromString("96f772f7-c083-4392-b9c6-443d83864675"),
                        "merchant-standard",
                        "tok_A1B2C3D4",
                        "APPROVED",
                        null),
                new Scenario(
                        "review merchant is declined",
                        UUID.fromString("b0b2f16b-7a49-4491-8760-d65017fd7f22"),
                        "merchant-review",
                        "tok_A1B2C3D4",
                        "DECLINED",
                        "FRAUD_REVIEW_REQUIRED"),
                new Scenario(
                        "high-risk merchant is declined",
                        UUID.fromString("ff78b3eb-3696-4b66-aac2-d24e32296118"),
                        "merchant-high-risk",
                        "tok_A1B2C3D4",
                        "DECLINED",
                        "HIGH_FRAUD_RISK"),
                new Scenario(
                        "insufficient-funds token is declined",
                        UUID.fromString("a9e55f35-3be6-42b7-a9e9-73df8fb91813"),
                        "merchant-standard",
                        "tok_insufficient01",
                        "DECLINED",
                        "INSUFFICIENT_FUNDS"),
                new Scenario(
                        "insufficient funds remains primary for high risk",
                        UUID.fromString("c6373029-ed4a-4970-815b-bb1a7149500f"),
                        "merchant-high-risk",
                        "tok_insufficient01",
                        "DECLINED",
                        "INSUFFICIENT_FUNDS"));
    }

    private static String requestJson(Scenario scenario) {
        return """
                {
                  "requestId": "%s",
                  "cardToken": "%s",
                  "merchantId": "%s",
                  "merchantCategoryCode": "5411",
                  "amount": 42.50,
                  "currency": "EUR",
                  "country": "DE",
                  "channel": "ECOMMERCE",
                  "transactionTime": "2026-07-19T10:15:30Z"
                }
                """.formatted(
                scenario.requestId(),
                scenario.cardToken(),
                scenario.merchantId());
    }

    private record Scenario(
            String description,
            UUID requestId,
            String merchantId,
            String cardToken,
            String decision,
            String declineReason) {

        @Override
        public String toString() {
            return description;
        }
    }
}
