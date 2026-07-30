package io.github.monssifechadli99.transactiq.authorization.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.authorization.adapter.out.memory.DeterministicFraudAssessmentAdapter;
import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationChannel;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.FraudAssessmentPort;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessment;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessmentResult;
import io.github.monssifechadli99.transactiq.authorization.support.AuthorizationServiceIntegrationTest;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
@Import(Cycle3HttpAcceptanceIntegrationTest.ControllableFraudConfiguration.class)
class Cycle3HttpAcceptanceIntegrationTest {

    private static final UUID IDENTICAL_REQUEST_ID =
            UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID BALANCE_REQUEST_ONE_ID =
            UUID.fromString("50000000-0000-4000-8000-000000000002");
    private static final UUID BALANCE_REQUEST_TWO_ID =
            UUID.fromString("50000000-0000-4000-8000-000000000003");
    private static final UUID TECHNICAL_RETRY_ID =
            UUID.fromString("50000000-0000-4000-8000-000000000004");
    private static final List<UUID> REQUEST_IDS = List.of(
            IDENTICAL_REQUEST_ID,
            BALANCE_REQUEST_ONE_ID,
            BALANCE_REQUEST_TWO_ID,
            TECHNICAL_RETRY_ID);
    private static final Instant TRANSACTION_TIME = Instant.parse("2026-07-20T10:15:30Z");

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ControllableFraudAssessmentAdapter fraudAdapter;

    @BeforeEach
    void resetAcceptanceState() {
        removePersistentTestData();
        fraudAdapter.reset();
    }

    @AfterEach
    void removeAcceptanceState() {
        fraudAdapter.releaseBlockedAssessment();
        removePersistentTestData();
    }

    @Test
    void concurrentIdenticalRequestReturnsPendingWhileFirstCompletesOnce() throws Exception {
        AuthorizationCommand command = command(
                IDENTICAL_REQUEST_ID, new BigDecimal("100.00"));
        fraudAdapter.blockNextAssessment();

        HttpResponse<String> firstResponse;
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<HttpResponse<String>> firstRequest = executor.submit(() -> post(command));
            assertTrue(fraudAdapter.awaitBlockedAssessment(10, TimeUnit.SECONDS));

            HttpResponse<String> duplicateResponse = post(command);
            assertEquals(202, duplicateResponse.statusCode());
            assertEquals(
                    "{\"requestId\":\"" + command.requestId() + "\",\"status\":\"PENDING\"}",
                    duplicateResponse.body());

            fraudAdapter.releaseBlockedAssessment();
            firstResponse = firstRequest.get(10, TimeUnit.SECONDS);
        } finally {
            fraudAdapter.releaseBlockedAssessment();
        }

        assertEquals(200, firstResponse.statusCode());
        assertTrue(firstResponse.body().contains("\"decision\":\"APPROVED\""));
        assertEquals(1, fraudAdapter.invocationCount());
        assertEquals(1, ledgerCount(List.of(command.requestId())));
        assertEquals(1, reservationCount(List.of(command.requestId())));
        assertEquals(1, completedRequestCount(List.of(command.requestId())));
    }

    @Test
    void concurrentDifferentRequestsSerializeAgainstTheSameAvailableBalance() throws Exception {
        AuthorizationCommand first = command(
                BALANCE_REQUEST_ONE_ID, new BigDecimal("600.00"));
        AuthorizationCommand second = command(
                BALANCE_REQUEST_TWO_ID, new BigDecimal("600.00"));
        fraudAdapter.synchronizeNextAssessments(2);

        List<HttpResponse<String>> responses;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> firstResponse = executor.submit(() -> post(first));
            Future<HttpResponse<String>> secondResponse = executor.submit(() -> post(second));
            assertTrue(fraudAdapter.awaitSynchronizedAssessments(10, TimeUnit.SECONDS));
            responses = List.of(
                    firstResponse.get(10, TimeUnit.SECONDS),
                    secondResponse.get(10, TimeUnit.SECONDS));
        }

        assertEquals(2, responses.stream().filter(response -> response.statusCode() == 200).count());
        assertEquals(
                1,
                responses.stream()
                        .filter(response -> response.body().contains("\"decision\":\"APPROVED\""))
                        .count());
        assertEquals(
                1,
                responses.stream()
                        .filter(response -> response.body().contains("\"decision\":\"DECLINED\""))
                        .count());
        assertEquals(
                1,
                responses.stream()
                        .filter(response -> response.body()
                                .contains("\"declineReason\":\"INSUFFICIENT_FUNDS\""))
                        .count());

        List<UUID> requestIds = List.of(first.requestId(), second.requestId());
        assertEquals(2, fraudAdapter.invocationCount());
        assertEquals(new BigDecimal("600.00"), reservedAmount());
        assertEquals(1, reservationCount(requestIds));
        assertEquals(2, ledgerCount(requestIds));
        assertEquals(1, decisionCount(requestIds, "APPROVED"));
        assertEquals(1, decisionCount(requestIds, "DECLINED"));
        assertEquals(2, completedRequestCount(requestIds));
    }

    @Test
    void technicalFailureReleasesClaimAndIdenticalRetryCompletes() throws Exception {
        AuthorizationCommand command = command(
                TECHNICAL_RETRY_ID, new BigDecimal("50.00"));
        fraudAdapter.failNextAssessment();

        HttpResponse<String> failedResponse = post(command);

        assertEquals(500, failedResponse.statusCode());
        assertEquals(
                "{\"code\":\"AUTHORIZATION_PROCESSING_ERROR\"}",
                failedResponse.body());
        assertEquals(0, requestCount(command.requestId()));
        assertEquals(0, ledgerCount(List.of(command.requestId())));
        assertEquals(0, reservationCount(List.of(command.requestId())));

        HttpResponse<String> retryResponse = post(command);

        assertEquals(200, retryResponse.statusCode());
        assertTrue(retryResponse.body().contains("\"decision\":\"APPROVED\""));
        assertEquals(2, fraudAdapter.invocationCount());
        assertEquals(1, ledgerCount(List.of(command.requestId())));
        assertEquals(1, reservationCount(List.of(command.requestId())));
        assertEquals(1, completedRequestCount(List.of(command.requestId())));
        assertEquals(new BigDecimal("50.00"), reservedAmount());
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

    private int ledgerCount(List<UUID> requestIds) {
        return countByRequestIds("authorization_ledger", requestIds);
    }

    private int reservationCount(List<UUID> requestIds) {
        return countByRequestIds("balance_reservations", requestIds);
    }

    private int countByRequestIds(String table, List<UUID> requestIds) {
        String sql = """
                SELECT COUNT(*)
                FROM "authorization".%s
                WHERE request_id IN (:requestIds)
                """.formatted(table);
        return jdbcClient.sql(sql)
                .param("requestIds", requestIds)
                .query(Integer.class)
                .single();
    }

    private int decisionCount(List<UUID> requestIds, String decision) {
        return jdbcClient.sql(
                        """
                        SELECT COUNT(*)
                        FROM "authorization".authorization_ledger
                        WHERE request_id IN (:requestIds)
                          AND decision = :decision
                        """)
                .param("requestIds", requestIds)
                .param("decision", decision)
                .query(Integer.class)
                .single();
    }

    private int completedRequestCount(List<UUID> requestIds) {
        return jdbcClient.sql(
                        """
                        SELECT COUNT(*)
                        FROM "authorization".authorization_requests
                        WHERE request_id IN (:requestIds)
                          AND status = 'COMPLETED'
                          AND completed_at IS NOT NULL
                        """)
                .param("requestIds", requestIds)
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

    private static AuthorizationCommand command(UUID requestId, BigDecimal amount) {
        return new AuthorizationCommand(
                requestId,
                "tok_A1B2C3D4",
                "merchant-standard",
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
    static class ControllableFraudConfiguration {

        @Bean
        @Primary
        ControllableFraudAssessmentAdapter controllableFraudAssessmentAdapter() {
            return new ControllableFraudAssessmentAdapter();
        }
    }

    static final class ControllableFraudAssessmentAdapter implements FraudAssessmentPort {

        private final DeterministicFraudAssessmentAdapter delegate =
                new DeterministicFraudAssessmentAdapter();
        private final AtomicInteger invocationCount = new AtomicInteger();
        private final AtomicBoolean blockNext = new AtomicBoolean();
        private final AtomicBoolean failNext = new AtomicBoolean();

        private volatile CountDownLatch blockedAssessmentEntered = new CountDownLatch(0);
        private volatile CountDownLatch blockedAssessmentRelease = new CountDownLatch(0);
        private volatile CountDownLatch synchronizedAssessments = new CountDownLatch(0);

        @Override
        public FraudAssessmentResult assess(AuthorizationCommand command) {
            invocationCount.incrementAndGet();

            if (failNext.compareAndSet(true, false)) {
                throw new IllegalStateException("synthetic fail-once fraud assessment");
            }

            if (blockNext.compareAndSet(true, false)) {
                blockedAssessmentEntered.countDown();
                await(blockedAssessmentRelease, "blocked fraud assessment was not released");
            }

            CountDownLatch assessmentBarrier = synchronizedAssessments;
            if (assessmentBarrier.getCount() > 0) {
                assessmentBarrier.countDown();
                await(assessmentBarrier, "concurrent fraud assessments did not rendezvous");
            }

            return delegate.assess(command);
        }

        private void blockNextAssessment() {
            blockedAssessmentEntered = new CountDownLatch(1);
            blockedAssessmentRelease = new CountDownLatch(1);
            blockNext.set(true);
        }

        private boolean awaitBlockedAssessment(long timeout, TimeUnit unit)
                throws InterruptedException {
            return blockedAssessmentEntered.await(timeout, unit);
        }

        private void releaseBlockedAssessment() {
            blockedAssessmentRelease.countDown();
        }

        private void synchronizeNextAssessments(int assessmentCount) {
            synchronizedAssessments = new CountDownLatch(assessmentCount);
        }

        private boolean awaitSynchronizedAssessments(long timeout, TimeUnit unit)
                throws InterruptedException {
            return synchronizedAssessments.await(timeout, unit);
        }

        private void failNextAssessment() {
            failNext.set(true);
        }

        private int invocationCount() {
            return invocationCount.get();
        }

        private void reset() {
            releaseBlockedAssessment();
            invocationCount.set(0);
            blockNext.set(false);
            failNext.set(false);
            blockedAssessmentEntered = new CountDownLatch(0);
            blockedAssessmentRelease = new CountDownLatch(0);
            synchronizedAssessments = new CountDownLatch(0);
        }

        private static void await(CountDownLatch latch, String failureMessage) {
            try {
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(failureMessage);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failureMessage, exception);
            }
        }
    }
}
