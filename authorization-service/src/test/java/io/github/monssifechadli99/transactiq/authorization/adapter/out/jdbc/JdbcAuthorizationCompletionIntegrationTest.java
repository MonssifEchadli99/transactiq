package io.github.monssifechadli99.transactiq.authorization.adapter.out.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.authorization.adapter.out.transaction.SpringTransactionExecutor;
import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationChannel;
import io.github.monssifechadli99.transactiq.authorization.application.model.IdempotencyClaimResult.Claimed;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.IdempotencyClaimPort;
import io.github.monssifechadli99.transactiq.authorization.application.service.AuthorizationCompletionService;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationOutcome;
import io.github.monssifechadli99.transactiq.authorization.domain.DeclineReason;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessment;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationPolicy;
import io.github.monssifechadli99.transactiq.authorization.support.AuthorizationServiceIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AuthorizationServiceIntegrationTest
class JdbcAuthorizationCompletionIntegrationTest {

    private static final UUID ACCOUNT_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID FAILURE_REQUEST_ID =
            UUID.fromString("30000000-0000-4000-8000-000000000005");
    private static final Instant TRANSACTION_TIME = Instant.parse("2026-07-20T10:15:30Z");

    private final Set<UUID> requestIds = ConcurrentHashMap.newKeySet();

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private IdempotencyClaimPort idempotencyClaimPort;

    private AuthorizationCompletionService completionService;

    @BeforeEach
    void createCompletionService() {
        completionService = new AuthorizationCompletionService(
                new SpringTransactionExecutor(new TransactionTemplate(transactionManager)),
                new JdbcNonFraudCheckAdapter(jdbcClient),
                new JdbcAuthorizationLedgerAdapter(jdbcClient),
                new AuthorizationPolicy());
    }

    @AfterEach
    void removeCompletionTestData() {
        jdbcClient.sql(
                        """
                        DROP TRIGGER IF EXISTS fail_authorization_completion_for_test
                        ON "authorization".authorization_requests
                        """)
                .update();
        jdbcClient.sql(
                        """
                        DROP FUNCTION IF EXISTS "authorization".fail_authorization_completion_for_test()
                        """)
                .update();

        for (UUID requestId : requestIds) {
            jdbcClient.sql(
                            """
                            DELETE FROM "authorization".balance_reservations
                            WHERE request_id = :requestId
                            """)
                    .param("requestId", requestId)
                    .update();
            jdbcClient.sql(
                            """
                            DELETE FROM "authorization".authorization_ledger
                            WHERE request_id = :requestId
                            """)
                    .param("requestId", requestId)
                    .update();
            jdbcClient.sql(
                            """
                            DELETE FROM "authorization".authorization_requests
                            WHERE request_id = :requestId
                            """)
                    .param("requestId", requestId)
                    .update();
        }

        jdbcClient.sql(
                        """
                        UPDATE "authorization".card_accounts
                        SET reserved_amount = 0.00,
                            updated_at = TIMESTAMPTZ '2026-07-19 00:00:00+00'
                        WHERE account_id = :accountId
                        """)
                .param("accountId", ACCOUNT_ID)
                .update();
    }

    @Test
    void approvalPersistsLedgerReservationAndReservedBalanceAtomically() {
        AuthorizationCommand command = command(
                "30000000-0000-4000-8000-000000000001", new BigDecimal("125.00"));
        claim(command);

        AuthorizationOutcome outcome = completionService.complete(command, FraudAssessment.CLEAR);

        assertInstanceOf(AuthorizationOutcome.Approved.class, outcome);
        assertEquals(new BigDecimal("125.00"), reservedAmount());
        assertEquals(
                new PersistedLedger("APPROVED", null, "CLEAR", "PASSED"),
                ledger(command.requestId()));
        PersistedReservation reservation = reservation(command.requestId());
        assertNotNull(reservation.reservationId());
        assertEquals(command.requestId(), reservation.requestId());
        assertEquals(ACCOUNT_ID, reservation.accountId());
        assertEquals(new BigDecimal("125.00"), reservation.amount());
        assertEquals("EUR", reservation.currency());
        assertEquals("ACTIVE", reservation.status());
        assertCompleted(command.requestId());
    }

    @Test
    void insufficientFundsDeclinePersistsLedgerWithoutChangingBalance() {
        AuthorizationCommand command = command(
                "30000000-0000-4000-8000-000000000002", new BigDecimal("1000.01"));
        claim(command);

        AuthorizationOutcome.Declined outcome = assertInstanceOf(
                AuthorizationOutcome.Declined.class,
                completionService.complete(command, FraudAssessment.CLEAR));

        assertEquals(DeclineReason.INSUFFICIENT_FUNDS, outcome.declineReason());
        assertEquals(new BigDecimal("0.00"), reservedAmount());
        assertEquals(0, reservationCount(command.requestId()));
        assertEquals(
                new PersistedLedger(
                        "DECLINED", "INSUFFICIENT_FUNDS", "CLEAR", "INSUFFICIENT_FUNDS"),
                ledger(command.requestId()));
        assertCompleted(command.requestId());
    }

    @Test
    void reviewDeclinePersistsFraudReviewReasonWithoutReservation() {
        AuthorizationCommand command = command(
                "30000000-0000-4000-8000-000000000003", new BigDecimal("75.00"));
        claim(command);

        AuthorizationOutcome.Declined outcome = assertInstanceOf(
                AuthorizationOutcome.Declined.class,
                completionService.complete(command, FraudAssessment.REVIEW));

        assertEquals(DeclineReason.FRAUD_REVIEW_REQUIRED, outcome.declineReason());
        assertEquals(
                new PersistedLedger(
                        "DECLINED", "FRAUD_REVIEW_REQUIRED", "REVIEW", "PASSED"),
                ledger(command.requestId()));
        assertEquals(new BigDecimal("0.00"), reservedAmount());
        assertEquals(0, reservationCount(command.requestId()));
        assertCompleted(command.requestId());
    }

    @Test
    void highRiskWithInsufficientFundsPreservesInsufficientFundsReason() {
        AuthorizationCommand command = command(
                "30000000-0000-4000-8000-000000000004", new BigDecimal("1000.01"));
        claim(command);

        AuthorizationOutcome.Declined outcome = assertInstanceOf(
                AuthorizationOutcome.Declined.class,
                completionService.complete(command, FraudAssessment.HIGH_RISK));

        assertEquals(DeclineReason.INSUFFICIENT_FUNDS, outcome.declineReason());
        assertTrue(outcome.fraudCaseRequired());
        assertEquals(
                new PersistedLedger(
                        "DECLINED",
                        "INSUFFICIENT_FUNDS",
                        "HIGH_RISK",
                        "INSUFFICIENT_FUNDS"),
                ledger(command.requestId()));
        assertEquals(0, reservationCount(command.requestId()));
        assertCompleted(command.requestId());
    }

    @Test
    void technicalFailureRollsBackLedgerReservationBalanceAndCompletion() {
        AuthorizationCommand command = command(FAILURE_REQUEST_ID.toString(), new BigDecimal("100.00"));
        claim(command);
        installCompletionFailureTrigger();

        assertThrows(
                RuntimeException.class,
                () -> completionService.complete(command, FraudAssessment.CLEAR));

        assertEquals(new BigDecimal("0.00"), reservedAmount());
        assertEquals(0, ledgerCount(command.requestId()));
        assertEquals(0, reservationCount(command.requestId()));
        PersistedRequest request = request(command.requestId());
        assertEquals("PENDING", request.status());
        assertFalse(request.completed());
    }

    @Test
    void concurrentRequestsAgainstSameBalanceProduceOneApprovalAndOneFundsDecline()
            throws Exception {
        AuthorizationCommand first = command(
                "30000000-0000-4000-8000-000000000006", new BigDecimal("600.00"));
        AuthorizationCommand second = command(
                "30000000-0000-4000-8000-000000000007", new BigDecimal("600.00"));
        claim(first);
        claim(second);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<AuthorizationOutcome> completeFirst =
                () -> completeConcurrently(first, ready, start);
        Callable<AuthorizationOutcome> completeSecond =
                () -> completeConcurrently(second, ready, start);

        List<AuthorizationOutcome> outcomes;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AuthorizationOutcome> firstOutcome = executor.submit(completeFirst);
            Future<AuthorizationOutcome> secondOutcome = executor.submit(completeSecond);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            outcomes = List.of(
                    firstOutcome.get(10, TimeUnit.SECONDS),
                    secondOutcome.get(10, TimeUnit.SECONDS));
        }

        assertEquals(
                1,
                outcomes.stream()
                        .filter(AuthorizationOutcome.Approved.class::isInstance)
                        .count());
        List<AuthorizationOutcome.Declined> declines = outcomes.stream()
                .filter(AuthorizationOutcome.Declined.class::isInstance)
                .map(AuthorizationOutcome.Declined.class::cast)
                .toList();
        assertEquals(1, declines.size());
        assertEquals(DeclineReason.INSUFFICIENT_FUNDS, declines.getFirst().declineReason());

        assertEquals(new BigDecimal("600.00"), reservedAmount());
        assertEquals(1, activeReservationCount());
        assertEquals(new BigDecimal("600.00"), activeReservationTotal());
        assertEquals(2, ledgerCount(List.of(first.requestId(), second.requestId())));
        assertEquals(1, decisionCount(List.of(first.requestId(), second.requestId()), "APPROVED"));
        assertEquals(1, decisionCount(List.of(first.requestId(), second.requestId()), "DECLINED"));
        assertEquals(2, completedRequestCount(List.of(first.requestId(), second.requestId())));
        assertTrue(reservedAmountIsWithinPostedBalance());
    }

    private AuthorizationOutcome completeConcurrently(
            AuthorizationCommand command, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return completionService.complete(command, FraudAssessment.CLEAR);
    }

    private void claim(AuthorizationCommand command) {
        requestIds.add(command.requestId());
        assertInstanceOf(Claimed.class, idempotencyClaimPort.claim(command));
    }

    private void installCompletionFailureTrigger() {
        jdbcClient.sql(
                        """
                        CREATE FUNCTION "authorization".fail_authorization_completion_for_test()
                        RETURNS trigger
                        LANGUAGE plpgsql
                        AS $$
                        BEGIN
                            IF NEW.request_id = '30000000-0000-4000-8000-000000000005'::uuid
                               AND NEW.status = 'COMPLETED' THEN
                                RAISE EXCEPTION 'forced authorization completion failure';
                            END IF;
                            RETURN NEW;
                        END;
                        $$
                        """)
                .update();
        jdbcClient.sql(
                        """
                        CREATE TRIGGER fail_authorization_completion_for_test
                        BEFORE UPDATE OF status
                        ON "authorization".authorization_requests
                        FOR EACH ROW
                        EXECUTE FUNCTION "authorization".fail_authorization_completion_for_test()
                        """)
                .update();
    }

    private BigDecimal reservedAmount() {
        return jdbcClient.sql(
                        """
                        SELECT reserved_amount
                        FROM "authorization".card_accounts
                        WHERE account_id = :accountId
                        """)
                .param("accountId", ACCOUNT_ID)
                .query(BigDecimal.class)
                .single();
    }

    private boolean reservedAmountIsWithinPostedBalance() {
        return jdbcClient.sql(
                        """
                        SELECT reserved_amount <= posted_balance
                        FROM "authorization".card_accounts
                        WHERE account_id = :accountId
                        """)
                .param("accountId", ACCOUNT_ID)
                .query(Boolean.class)
                .single();
    }

    private PersistedLedger ledger(UUID requestId) {
        return jdbcClient.sql(
                        """
                        SELECT decision, decline_reason, fraud_assessment, non_fraud_check_result
                        FROM "authorization".authorization_ledger
                        WHERE request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query((resultSet, rowNumber) -> new PersistedLedger(
                        resultSet.getString("decision"),
                        resultSet.getString("decline_reason"),
                        resultSet.getString("fraud_assessment"),
                        resultSet.getString("non_fraud_check_result")))
                .single();
    }

    private PersistedReservation reservation(UUID requestId) {
        return jdbcClient.sql(
                        """
                        SELECT reservation_id, request_id, account_id, amount, currency, status
                        FROM "authorization".balance_reservations
                        WHERE request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query((resultSet, rowNumber) -> new PersistedReservation(
                        resultSet.getObject("reservation_id", UUID.class),
                        resultSet.getObject("request_id", UUID.class),
                        resultSet.getObject("account_id", UUID.class),
                        resultSet.getBigDecimal("amount"),
                        resultSet.getString("currency"),
                        resultSet.getString("status")))
                .single();
    }

    private PersistedRequest request(UUID requestId) {
        return jdbcClient.sql(
                        """
                        SELECT status, completed_at IS NOT NULL AS completed
                        FROM "authorization".authorization_requests
                        WHERE request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query((resultSet, rowNumber) -> new PersistedRequest(
                        resultSet.getString("status"), resultSet.getBoolean("completed")))
                .single();
    }

    private void assertCompleted(UUID requestId) {
        PersistedRequest request = request(requestId);
        assertEquals("COMPLETED", request.status());
        assertTrue(request.completed());
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

    private int ledgerCount(List<UUID> requestIdsToCount) {
        return jdbcClient.sql(
                        """
                        SELECT COUNT(*)
                        FROM "authorization".authorization_ledger
                        WHERE request_id IN (:requestIds)
                        """)
                .param("requestIds", requestIdsToCount)
                .query(Integer.class)
                .single();
    }

    private int decisionCount(List<UUID> requestIdsToCount, String decision) {
        return jdbcClient.sql(
                        """
                        SELECT COUNT(*)
                        FROM "authorization".authorization_ledger
                        WHERE request_id IN (:requestIds)
                          AND decision = :decision
                        """)
                .param("requestIds", requestIdsToCount)
                .param("decision", decision)
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

    private int activeReservationCount() {
        return jdbcClient.sql(
                        """
                        SELECT COUNT(*)
                        FROM "authorization".balance_reservations
                        WHERE account_id = :accountId
                          AND status = 'ACTIVE'
                        """)
                .param("accountId", ACCOUNT_ID)
                .query(Integer.class)
                .single();
    }

    private BigDecimal activeReservationTotal() {
        return jdbcClient.sql(
                        """
                        SELECT COALESCE(SUM(amount), 0.00)
                        FROM "authorization".balance_reservations
                        WHERE account_id = :accountId
                          AND status = 'ACTIVE'
                        """)
                .param("accountId", ACCOUNT_ID)
                .query(BigDecimal.class)
                .single();
    }

    private int completedRequestCount(List<UUID> requestIdsToCount) {
        return jdbcClient.sql(
                        """
                        SELECT COUNT(*)
                        FROM "authorization".authorization_requests
                        WHERE request_id IN (:requestIds)
                          AND status = 'COMPLETED'
                          AND completed_at IS NOT NULL
                        """)
                .param("requestIds", requestIdsToCount)
                .query(Integer.class)
                .single();
    }

    private static AuthorizationCommand command(String requestId, BigDecimal amount) {
        return new AuthorizationCommand(
                UUID.fromString(requestId),
                "tok_A1B2C3D4",
                "merchant-standard",
                "5411",
                amount,
                "EUR",
                "DE",
                AuthorizationChannel.ECOMMERCE,
                TRANSACTION_TIME);
    }

    private record PersistedLedger(
            String decision,
            String declineReason,
            String fraudAssessment,
            String nonFraudCheckResult) {}

    private record PersistedReservation(
            UUID reservationId,
            UUID requestId,
            UUID accountId,
            BigDecimal amount,
            String currency,
            String status) {}

    private record PersistedRequest(String status, boolean completed) {}
}
