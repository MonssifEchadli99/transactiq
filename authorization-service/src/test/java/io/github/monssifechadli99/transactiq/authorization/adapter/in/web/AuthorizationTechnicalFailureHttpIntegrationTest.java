package io.github.monssifechadli99.transactiq.authorization.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizeTransactionUseCase;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationOutcome;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(AuthorizationTechnicalFailureHttpIntegrationTest.FailingUseCaseConfiguration.class)
class AuthorizationTechnicalFailureHttpIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void unexpectedFailureReturnsGenericCodeWithoutInternalDetails() throws Exception {
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
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingUseCaseConfiguration {

        @Bean
        @Primary
        AuthorizeTransactionUseCase failingAuthorizeTransactionUseCase() {
            return new FailingAuthorizeTransactionUseCase();
        }
    }

    private static final class FailingAuthorizeTransactionUseCase
            implements AuthorizeTransactionUseCase {

        @Override
        public AuthorizationOutcome authorize(AuthorizationCommand command) {
            throw new IllegalStateException("sensitive internal failure");
        }
    }
}
