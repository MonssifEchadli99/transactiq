package io.github.monssifechadli99.transactiq.authorization.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.authorization.support.AuthorizationServiceIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

@AuthorizationServiceIntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AuthorizationHttpErrorIntegrationTest {

    private static final UUID UNKNOWN_EUR_REQUEST_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID KNOWN_NON_EUR_REQUEST_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID UNKNOWN_NON_EUR_REQUEST_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000003");

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbcClient;

    @AfterEach
    void removeRejectedRequestTestData() {
        jdbcClient.sql(
                        """
                        DELETE FROM "authorization".authorization_requests
                        WHERE request_id IN (:requestIds)
                        """)
                .param(
                        "requestIds",
                        List.of(
                                UNKNOWN_EUR_REQUEST_ID,
                                KNOWN_NON_EUR_REQUEST_ID,
                                UNKNOWN_NON_EUR_REQUEST_ID))
                .update();
    }

    @Test
    void returnsEveryInvalidFieldInDeterministicOrderWithoutRecording() throws Exception {
        int ledgerSizeBefore = ledgerCount();
        String request = """
                {
                  "requestId": "e55f1e40-6974-46d5-8a87-3cc052377bbb",
                  "cardToken": "invalid-token",
                  "merchantId": "merchant-standard",
                  "merchantCategoryCode": "54A",
                  "amount": 0,
                  "currency": "eur",
                  "country": "de",
                  "channel": "ECOMMERCE",
                  "transactionTime": "2026-07-19T10:15:30Z"
                }
                """;

        HttpResponse<String> response = post(request);

        assertEquals(400, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("\"code\":\"INVALID_AUTHORIZATION_REQUEST\""), body);
        assertTrue(body.contains("\"fieldErrors\""), body);
        assertEquals(5, occurrences(body, "\"field\""));
        assertFieldsAppearInOrder(
                body,
                List.of("amount", "cardToken", "country", "currency", "merchantCategoryCode"));
        assertTrue(body.contains("\"message\""), body);
        assertEquals(ledgerSizeBefore, ledgerCount());
    }

    @Test
    void malformedJsonReturnsCodeOnlyWithoutRecording() throws Exception {
        int ledgerSizeBefore = ledgerCount();

        HttpResponse<String> response = post("{not-json");

        assertEquals(400, response.statusCode());
        assertEquals("{\"code\":\"MALFORMED_AUTHORIZATION_REQUEST\"}", response.body());
        assertEquals(ledgerSizeBefore, ledgerCount());
    }

    @Test
    void unknownCardTokenWithEurReturnsUnknownTokenWithoutRecording() throws Exception {
        int ledgerSizeBefore = ledgerCount();

        HttpResponse<String> response = post(validRequestJson(
                UNKNOWN_EUR_REQUEST_ID,
                "tok_unknown0001",
                "EUR"));

        assertEquals(400, response.statusCode());
        assertEquals("{\"code\":\"UNKNOWN_CARD_TOKEN\"}", response.body());
        assertEquals(ledgerSizeBefore, ledgerCount());
        assertEquals(0, requestCount(UNKNOWN_EUR_REQUEST_ID));
    }

    @Test
    void knownCardTokenWithNonEurReturnsUnsupportedCurrencyWithoutRecording() throws Exception {
        int ledgerSizeBefore = ledgerCount();

        HttpResponse<String> response = post(validRequestJson(
                KNOWN_NON_EUR_REQUEST_ID,
                "tok_A1B2C3D4",
                "USD"));

        assertEquals(400, response.statusCode());
        assertEquals("{\"code\":\"UNSUPPORTED_CURRENCY\"}", response.body());
        assertEquals(ledgerSizeBefore, ledgerCount());
        assertEquals(0, requestCount(KNOWN_NON_EUR_REQUEST_ID));
    }

    @Test
    void unknownCardTokenWithNonEurReturnsUnsupportedCurrencyWithoutRecording() throws Exception {
        int ledgerSizeBefore = ledgerCount();

        HttpResponse<String> response = post(validRequestJson(
                UNKNOWN_NON_EUR_REQUEST_ID,
                "tok_unknown0001",
                "USD"));

        assertEquals(400, response.statusCode());
        assertEquals("{\"code\":\"UNSUPPORTED_CURRENCY\"}", response.body());
        assertEquals(ledgerSizeBefore, ledgerCount());
        assertEquals(0, requestCount(UNKNOWN_NON_EUR_REQUEST_ID));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedRepresentations")
    void malformedRepresentationReturnsCodeOnlyWithoutRecording(MalformedScenario scenario)
            throws Exception {
        int ledgerSizeBefore = ledgerCount();

        HttpResponse<String> response = post(requestJson(scenario));

        assertEquals(400, response.statusCode());
        assertEquals("{\"code\":\"MALFORMED_AUTHORIZATION_REQUEST\"}", response.body());
        assertEquals(ledgerSizeBefore, ledgerCount());
    }

    private int ledgerCount() {
        return jdbcClient.sql(
                        """
                        SELECT COUNT(*)
                        FROM "authorization".authorization_ledger
                        """)
                .query(Integer.class)
                .single();
    }

    private int requestCount(UUID requestId) {
        return jdbcClient.sql(
                        """
                        SELECT COUNT(*)
                        FROM "authorization".authorization_requests
                        WHERE request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query(Integer.class)
                .single();
    }

    private HttpResponse<String> post(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/authorizations"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Stream<MalformedScenario> malformedRepresentations() {
        return Stream.of(
                new MalformedScenario(
                        "malformed UUID",
                        "not-a-uuid",
                        "42.50",
                        "ECOMMERCE",
                        "2026-07-19T10:15:30Z"),
                new MalformedScenario(
                        "malformed amount",
                        "4929244e-4017-4142-8662-234ea58e646a",
                        "\"not-a-number\"",
                        "ECOMMERCE",
                        "2026-07-19T10:15:30Z"),
                new MalformedScenario(
                        "malformed channel",
                        "65e902e7-de8a-4be4-9185-50c5187b31ca",
                        "42.50",
                        "MAIL_ORDER",
                        "2026-07-19T10:15:30Z"),
                new MalformedScenario(
                        "malformed transactionTime",
                        "7711ad7f-61b6-4bb4-833b-4f57598b1e39",
                        "42.50",
                        "ECOMMERCE",
                        "not-an-instant"));
    }

    private static String requestJson(MalformedScenario scenario) {
        return """
                {
                  "requestId": "%s",
                  "cardToken": "tok_A1B2C3D4",
                  "merchantId": "merchant-standard",
                  "merchantCategoryCode": "5411",
                  "amount": %s,
                  "currency": "EUR",
                  "country": "DE",
                  "channel": "%s",
                  "transactionTime": "%s"
                }
                """.formatted(
                scenario.requestId(),
                scenario.amountJson(),
                scenario.channel(),
                scenario.transactionTime());
    }

    private static String validRequestJson(UUID requestId, String cardToken, String currency) {
        return """
                {
                  "requestId": "%s",
                  "cardToken": "%s",
                  "merchantId": "merchant-standard",
                  "merchantCategoryCode": "5411",
                  "amount": 42.50,
                  "currency": "%s",
                  "country": "DE",
                  "channel": "ECOMMERCE",
                  "transactionTime": "2026-07-20T10:15:30Z"
                }
                """.formatted(requestId, cardToken, currency);
    }

    private static int occurrences(String value, String target) {
        int count = 0;
        int position = 0;
        while ((position = value.indexOf(target, position)) >= 0) {
            count++;
            position += target.length();
        }
        return count;
    }

    private static void assertFieldsAppearInOrder(String body, List<String> fields) {
        int previousPosition = -1;
        for (String field : fields) {
            int position = body.indexOf("\"field\":\"" + field + "\"");
            assertTrue(position > previousPosition, body);
            previousPosition = position;
        }
    }

    private record MalformedScenario(
            String description,
            String requestId,
            String amountJson,
            String channel,
            String transactionTime) {

        @Override
        public String toString() {
            return description;
        }
    }
}
