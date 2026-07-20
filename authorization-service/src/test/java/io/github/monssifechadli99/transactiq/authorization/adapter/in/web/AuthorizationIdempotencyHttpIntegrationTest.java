package io.github.monssifechadli99.transactiq.authorization.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.authorization.adapter.out.memory.DeterministicFraudAssessmentAdapter;
import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationChannel;
import io.github.monssifechadli99.transactiq.authorization.application.model.IdempotencyClaimResult.Claimed;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.FraudAssessmentPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.IdempotencyClaimPort;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessment;
import io.github.monssifechadli99.transactiq.authorization.support.AuthorizationServiceIntegrationTest;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
@Import(AuthorizationIdempotencyHttpIntegrationTest.CountingFraudConfiguration.class)
class AuthorizationIdempotencyHttpIntegrationTest {

    private static final UUID FIRST_APPROVAL_ID =
            UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID COMPLETED_RETRY_ID =
            UUID.fromString("40000000-0000-4000-8000-000000000002");
    private static final UUID PENDING_RETRY_ID =
            UUID.fromString("40000000-0000-4000-8000-000000000003");
    private static final UUID CONFLICT_ID =
            UUID.fromString("40000000-0000-4000-8000-000000000004");
    private static final UUID DECLINED_RETRY_ID =
            UUID.fromString("40000000-0000-4000-8000-000000000005");
    private static final List<UUID> REQUEST_IDS = List.of(
            FIRST_APPROVAL_ID,
            COMPLETED_RETRY_ID,
            PENDING_RETRY_ID,
            CONFLICT_ID,
            DECLINED_RETRY_ID);
    private static final Instant TRANSACTION_TIME = Instant.parse("2026-07-20T10:15:30Z");

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private IdempotencyClaimPort idempotencyClaimPort;

    @Autowired
    private CountingFraudAssessmentAdapter fraudAdapter;

    @BeforeEach
    void resetTestState() {
        removePersistentTestData();
        fraudAdapter.reset();
    }

    @AfterEach
    void removeTestState() {
        removePersistentTestData();
    }

    @Test
    void firstApprovalPersistsOneLedgerEntryAndOneReservation() throws Exception {
        AuthorizationCommand command = command(
                FIRST_APPROVAL_ID, "merchant-standard", new BigDecimal("100.00"));

        HttpResponse<String> response = post(command);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"decision\":\"APPROVED\""), response.body());
        assertEquals(1, fraudAdapter.invocationCount());
        assertEquals(1, ledgerCount(command.requestId()));
        assertEquals(1, reservationCount(command.requestId()));
        assertEquals(new BigDecimal("100.00"), reservedAmount());
    }

    @Test
    void identicalCompletedRetryReturnsOriginalResponseWithoutRepeatingWork() throws Exception {
        AuthorizationCommand command = command(
                COMPLETED_RETRY_ID, "merchant-standard", new BigDecimal("80.00"));

        HttpResponse<String> first = post(command);
        HttpResponse<String> retry = post(command);

        assertEquals(200, first.statusCode());
        assertEquals(200, retry.statusCode());
        assertEquals(first.body(), retry.body());
        assertEquals(1, fraudAdapter.invocationCount());
        assertEquals(1, ledgerCount(command.requestId()));
        assertEquals(1, reservationCount(command.requestId()));
        assertEquals(new BigDecimal("80.00"), reservedAmount());
    }

    @Test
    void identicalPendingRetryReturnsAcceptedWithoutChangingBalance() throws Exception {
        AuthorizationCommand command = command(
                PENDING_RETRY_ID, "merchant-standard", new BigDecimal("60.00"));
        assertInstanceOf(Claimed.class, idempotencyClaimPort.claim(command));

        HttpResponse<String> response = post(command);

        assertEquals(202, response.statusCode());
        assertEquals(
                "{\"requestId\":\"" + command.requestId() + "\",\"status\":\"PENDING\"}",
                response.body());
        assertEquals(0, fraudAdapter.invocationCount());
        assertEquals(0, ledgerCount(command.requestId()));
        assertEquals(0, reservationCount(command.requestId()));
        assertEquals(new BigDecimal("0.00"), reservedAmount());
    }

    @Test
    void conflictingPayloadReturnsConflictWithoutChangingBalance() throws Exception {
        AuthorizationCommand original = command(
                CONFLICT_ID, "merchant-standard", new BigDecimal("10.00"));
        AuthorizationCommand conflicting = command(
                CONFLICT_ID, "merchant-standard", new BigDecimal("20.00"));
        assertInstanceOf(Claimed.class, idempotencyClaimPort.claim(original));

        HttpResponse<String> response = post(conflicting);

        assertEquals(409, response.statusCode());
        assertEquals("{\"code\":\"REQUEST_ID_CONFLICT\"}", response.body());
        assertEquals(0, fraudAdapter.invocationCount());
        assertEquals(0, ledgerCount(CONFLICT_ID));
        assertEquals(0, reservationCount(CONFLICT_ID));
        assertEquals(new BigDecimal("0.00"), reservedAmount());
    }

    @Test
    void declinedRetryReturnsOriginalDeclineWithoutAnotherLedgerEntry() throws Exception {
        AuthorizationCommand command = command(
                DECLINED_RETRY_ID, "merchant-review", new BigDecimal("75.00"));

        HttpResponse<String> first = post(command);
        HttpResponse<String> retry = post(command);

        assertEquals(200, first.statusCode());
        assertEquals(200, retry.statusCode());
        assertEquals(first.body(), retry.body());
        assertTrue(
                retry.body().contains("\"declineReason\":\"FRAUD_REVIEW_REQUIRED\""),
                retry.body());
        assertEquals(1, fraudAdapter.invocationCount());
        assertEquals(1, ledgerCount(command.requestId()));
        assertEquals(0, reservationCount(command.requestId()));
        assertEquals(new BigDecimal("0.00"), reservedAmount());
    }

    private HttpResponse<String> post(AuthorizationCommand command) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/authorizations"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson(command)))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void removePersistentTestData() {
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

    private int ledgerCount(UUID requestId) {
        return jdbcClient.sql(
                        """
                        SELECT COUNT(*)
                        FROM "authorization".authorization_ledger
                        WHERE request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query(Integer.class)
                .single();
    }

    private int reservationCount(UUID requestId) {
        return jdbcClient.sql(
                        """
                        SELECT COUNT(*)
                        FROM "authorization".balance_reservations
                        WHERE request_id = :requestId
                        """)
                .param("requestId", requestId)
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

    private static AuthorizationCommand command(
            UUID requestId, String merchantId, BigDecimal amount) {
        return new AuthorizationCommand(
                requestId,
                "tok_A1B2C3D4",
                merchantId,
                "5411",
                amount,
                "EUR",
                "DE",
                AuthorizationChannel.ECOMMERCE,
                TRANSACTION_TIME);
    }

    private static String requestJson(AuthorizationCommand command) {
        return """
                {
                  "requestId": "%s",
                  "cardToken": "%s",
                  "merchantId": "%s",
                  "merchantCategoryCode": "%s",
                  "amount": %s,
                  "currency": "%s",
                  "country": "%s",
                  "channel": "%s",
                  "transactionTime": "%s"
                }
                """.formatted(
                command.requestId(),
                command.cardToken(),
                command.merchantId(),
                command.merchantCategoryCode(),
                command.amount().toPlainString(),
                command.currency(),
                command.country(),
                command.channel(),
                command.transactionTime());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CountingFraudConfiguration {

        @Bean
        @Primary
        CountingFraudAssessmentAdapter countingFraudAssessmentAdapter() {
            return new CountingFraudAssessmentAdapter();
        }
    }

    static final class CountingFraudAssessmentAdapter implements FraudAssessmentPort {

        private final AtomicInteger invocationCount = new AtomicInteger();
        private final DeterministicFraudAssessmentAdapter delegate =
                new DeterministicFraudAssessmentAdapter();

        @Override
        public FraudAssessment assess(AuthorizationCommand command) {
            invocationCount.incrementAndGet();
            return delegate.assess(command);
        }

        private int invocationCount() {
            return invocationCount.get();
        }

        private void reset() {
            invocationCount.set(0);
        }
    }
}
