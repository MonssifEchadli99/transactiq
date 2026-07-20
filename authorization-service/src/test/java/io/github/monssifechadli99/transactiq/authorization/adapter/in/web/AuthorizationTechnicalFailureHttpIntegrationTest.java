package io.github.monssifechadli99.transactiq.authorization.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.FraudAssessmentPort;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessment;
import io.github.monssifechadli99.transactiq.authorization.support.AuthorizationServiceIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

@AuthorizationServiceIntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(AuthorizationTechnicalFailureHttpIntegrationTest.FailingUseCaseConfiguration.class)
class AuthorizationTechnicalFailureHttpIntegrationTest {

    private static final UUID REQUEST_ID =
            UUID.fromString("61786cdd-85ef-426c-8fe9-9db0a6072747");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbcClient;

    @AfterEach
    void removeTechnicalFailureTestData() {
        jdbcClient.sql(
                        """
                        DELETE FROM "authorization".authorization_requests
                        WHERE request_id = :requestId
                        """)
                .param("requestId", REQUEST_ID)
                .update();
    }

    @Test
    void unexpectedFailureReturnsGenericCodeWithoutInternalDetails() throws Exception {
        BigDecimal reservedAmountBefore = reservedAmount();
        String request = """
                {
                  "requestId": "61786cdd-85ef-426c-8fe9-9db0a6072747",
                  "cardToken": "tok_A1B2C3D4",
                  "merchantId": "merchant-standard",
                  "merchantCategoryCode": "5411",
                  "amount": 42.50,
                  "currency": "EUR",
                  "country": "DE",
                  "channel": "ECOMMERCE",
                  "transactionTime": "2026-07-19T10:15:30Z"
                }
                """;
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/authorizations"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(httpRequest, HttpResponse.BodyHandlers.ofString());

        assertEquals(500, response.statusCode());
        assertEquals("{\"code\":\"AUTHORIZATION_PROCESSING_ERROR\"}", response.body());
        assertFalse(response.body().contains("IllegalStateException"));
        assertFalse(response.body().contains("sensitive internal failure"));
        assertFalse(response.body().contains("61786cdd-85ef-426c-8fe9-9db0a6072747"));
        assertFalse(response.body().contains("tok_A1B2C3D4"));
        assertEquals(0, requestCount());
        assertEquals(reservedAmountBefore, reservedAmount());
    }

    private int requestCount() {
        return jdbcClient.sql(
                        """
                        SELECT COUNT(*)
                        FROM "authorization".authorization_requests
                        WHERE request_id = :requestId
                        """)
                .param("requestId", REQUEST_ID)
                .query(Integer.class)
                .single();
    }

    private BigDecimal reservedAmount() {
        return jdbcClient.sql(
                        """
                        SELECT reserved_amount
                        FROM "authorization".card_accounts
                        WHERE card_token = 'tok_A1B2C3D4'
                        """)
                .query(BigDecimal.class)
                .single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingUseCaseConfiguration {

        @Bean
        @Primary
        FraudAssessmentPort technicallyFailingFraudAdapter() {
            return new TechnicallyFailingFraudAdapter();
        }
    }

    private static final class TechnicallyFailingFraudAdapter implements FraudAssessmentPort {

        @Override
        public FraudAssessment assess(AuthorizationCommand command) {
            throw new IllegalStateException("sensitive internal failure");
        }
    }
}
