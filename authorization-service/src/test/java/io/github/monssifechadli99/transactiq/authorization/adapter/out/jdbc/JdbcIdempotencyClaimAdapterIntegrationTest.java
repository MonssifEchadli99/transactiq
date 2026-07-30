package io.github.monssifechadli99.transactiq.authorization.adapter.out.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationChannel;
import io.github.monssifechadli99.transactiq.authorization.application.model.IdempotencyClaimResult;
import io.github.monssifechadli99.transactiq.authorization.application.model.IdempotencyClaimResult.Claimed;
import io.github.monssifechadli99.transactiq.authorization.application.model.IdempotencyClaimResult.Completed;
import io.github.monssifechadli99.transactiq.authorization.application.model.IdempotencyClaimResult.Conflict;
import io.github.monssifechadli99.transactiq.authorization.application.model.IdempotencyClaimResult.Pending;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.IdempotencyClaimPort;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationOutcome;
import io.github.monssifechadli99.transactiq.authorization.domain.DeclineReason;
import io.github.monssifechadli99.transactiq.authorization.support.AuthorizationServiceIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@AuthorizationServiceIntegrationTest
class JdbcIdempotencyClaimAdapterIntegrationTest {

    private static final Instant TRANSACTION_TIME = Instant.parse("2026-07-20T10:15:30Z");

    private final Set<UUID> requestIds = ConcurrentHashMap.newKeySet();

    @Autowired
    private IdempotencyClaimPort idempotencyClaimPort;

    @Autowired
    private JdbcClient jdbcClient;

    @AfterEach
    void removeClaimTestData() {
        for (UUID requestId : requestIds) {
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
    }

    @Test
    void firstClaimPersistsPendingFingerprintAndEveryCommandField() {
        AuthorizationCommand command = standardCommand(
                UUID.fromString("20000000-0000-4000-8000-000000000001"));

        assertInstanceOf(Claimed.class, claim(command));

        PersistedRequest persisted = persistedRequest(command.requestId());
        assertEquals("PENDING", persisted.status());
        assertTrue(persisted.fingerprint().matches("[0-9a-f]{64}"));
        assertEquals(command.requestId().toString(), persisted.requestId());
        assertEquals(command.cardToken(), persisted.cardToken());
        assertEquals(command.merchantId(), persisted.merchantId());
        assertEquals(command.merchantCategoryCode(), persisted.merchantCategoryCode());
        assertEquals("10", persisted.amount());
        assertEquals(command.currency(), persisted.currency());
        assertEquals(command.country(), persisted.country());
        assertEquals(command.channel().name(), persisted.channel());
        assertEquals(command.transactionTime().toString(), persisted.transactionTime());
    }

    @Test
    void identicalPendingRetryReturnsPending() {
        AuthorizationCommand command = standardCommand(
                UUID.fromString("20000000-0000-4000-8000-000000000002"));

        assertInstanceOf(Claimed.class, claim(command));
        assertInstanceOf(Pending.class, claim(command));
    }

    @Test
    void completedApprovedRetryReturnsStoredOutcomeAndCannotBeReleased() {
        AuthorizationCommand command = standardCommand(
                UUID.fromString("20000000-0000-4000-8000-000000000003"));
        assertInstanceOf(Claimed.class, claim(command));
        completeApproved(command.requestId());

        assertFalse(idempotencyClaimPort.releasePending(command.requestId()));
        Completed completed = assertInstanceOf(Completed.class, claim(command));
        assertEquals(new AuthorizationOutcome.Approved(false), completed.outcome());
        assertEquals(0, completed.fraudAssessment().riskScore());
    }

    @Test
    void completedReviewApprovalRetryPreservesFraudCaseRequirement() {
        AuthorizationCommand command = standardCommand(
                UUID.fromString("20000000-0000-4000-8000-000000000009"));
        assertInstanceOf(Claimed.class, claim(command));
        completeApprovedForReview(command.requestId());

        Completed completed = assertInstanceOf(Completed.class, claim(command));
        assertEquals(new AuthorizationOutcome.Approved(true), completed.outcome());
        assertEquals(15, completed.fraudAssessment().riskScore());
        assertEquals(15, completed.fraudAssessment().matchedRules().getFirst().scoreContribution());
    }

    @Test
    void completedDeclinedRetryReturnsStoredOutcome() {
        AuthorizationCommand command = standardCommand(
                UUID.fromString("20000000-0000-4000-8000-000000000004"));
        assertInstanceOf(Claimed.class, claim(command));
        completeDeclinedForReview(command.requestId());

        Completed completed = assertInstanceOf(Completed.class, claim(command));
        assertEquals(
                new AuthorizationOutcome.Declined(
                        DeclineReason.FRAUD_REVIEW_REQUIRED,
                        true),
                completed.outcome());
        assertEquals(15, completed.fraudAssessment().riskScore());
        assertEquals(15, completed.fraudAssessment().matchedRules().getFirst().scoreContribution());
    }

    @ParameterizedTest(name = "changed {0} returns conflict")
    @MethodSource("conflictingCommands")
    void changedCanonicalFieldReturnsConflict(
            String changedField,
            AuthorizationCommand original,
            AuthorizationCommand conflicting) {
        assertInstanceOf(Claimed.class, claim(original));
        assertInstanceOf(Conflict.class, claim(conflicting));
    }

    @Test
    void numericallyEqualAmountAndEquivalentInstantReturnPending() {
        UUID requestId = UUID.fromString("20000000-0000-4000-8000-000000000006");
        AuthorizationCommand first = command(
                requestId,
                "tok_A1B2C3D4",
                "merchant-standard",
                "5411",
                new BigDecimal("10.0"),
                "EUR",
                "DE",
                AuthorizationChannel.ECOMMERCE,
                Instant.parse("2026-07-20T10:15:30Z"));
        AuthorizationCommand equivalent = command(
                requestId,
                "tok_A1B2C3D4",
                "merchant-standard",
                "5411",
                new BigDecimal("10.00"),
                "EUR",
                "DE",
                AuthorizationChannel.ECOMMERCE,
                OffsetDateTime.parse("2026-07-20T12:15:30+02:00").toInstant());

        assertInstanceOf(Claimed.class, claim(first));
        assertInstanceOf(Pending.class, claim(equivalent));
    }

    @Test
    void releasingPendingClaimAllowsAReplacementClaim() {
        AuthorizationCommand command = standardCommand(
                UUID.fromString("20000000-0000-4000-8000-000000000007"));
        assertInstanceOf(Claimed.class, claim(command));

        assertTrue(idempotencyClaimPort.releasePending(command.requestId()));
        assertEquals(0, requestCount(command.requestId()));
        assertInstanceOf(Claimed.class, claim(command));
    }

    @Test
    void simultaneousNewClaimsProduceOneClaimedAndOnePending() throws Exception {
        AuthorizationCommand command = standardCommand(
                UUID.fromString("20000000-0000-4000-8000-000000000008"));
        requestIds.add(command.requestId());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<IdempotencyClaimResult> concurrentClaim = () -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            return idempotencyClaimPort.claim(command);
        };

        List<IdempotencyClaimResult> results;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<IdempotencyClaimResult> first = executor.submit(concurrentClaim);
            Future<IdempotencyClaimResult> second = executor.submit(concurrentClaim);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            results = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));
        }

        assertEquals(1, results.stream().filter(Claimed.class::isInstance).count());
        assertEquals(1, results.stream().filter(Pending.class::isInstance).count());
        assertEquals(1, requestCount(command.requestId()));
    }

    private IdempotencyClaimResult claim(AuthorizationCommand command) {
        requestIds.add(command.requestId());
        return idempotencyClaimPort.
                claim(command);
    }

    private PersistedRequest persistedRequest(UUID requestId) {
        return jdbcClient.sql(
                        """
                        SELECT status,
                               request_fingerprint,
                               request_payload ->> 'requestId' AS payload_request_id,
                               request_payload ->> 'cardToken' AS card_token,
                               request_payload ->> 'merchantId' AS merchant_id,
                               request_payload ->> 'merchantCategoryCode' AS merchant_category_code,
                               request_payload ->> 'amount' AS amount,
                               request_payload ->> 'currency' AS currency,
                               request_payload ->> 'country' AS country,
                               request_payload ->> 'channel' AS channel,
                               request_payload ->> 'transactionTime' AS transaction_time
                        FROM "authorization".authorization_requests
                        WHERE request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query((resultSet, rowNumber) -> new PersistedRequest(
                        resultSet.getString("status"),
                        resultSet.getString("request_fingerprint"),
                        resultSet.getString("payload_request_id"),
                        resultSet.getString("card_token"),
                        resultSet.getString("merchant_id"),
                        resultSet.getString("merchant_category_code"),
                        resultSet.getString("amount"),
                        resultSet.getString("currency"),
                        resultSet.getString("country"),
                        resultSet.getString("channel"),
                        resultSet.getString("transaction_time")))
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

    private void completeApproved(UUID requestId) {
        jdbcClient.sql(
                        """
                        INSERT INTO "authorization".authorization_ledger (
                            request_id,
                            decision,
                            fraud_assessment,
                            risk_score,
                            non_fraud_check_result
                        ) VALUES (:requestId, 'APPROVED', 'CLEAR', 0, 'PASSED')
                        """)
                .param("requestId", requestId)
                .update();
        markCompleted(requestId);
    }

    private void completeApprovedForReview(UUID requestId) {
        jdbcClient.sql(
                        """
                        INSERT INTO "authorization".authorization_ledger (
                            request_id,
                            decision,
                            fraud_assessment,
                            risk_score,
                            non_fraud_check_result
                        ) VALUES (:requestId, 'APPROVED', 'REVIEW', 15, 'PASSED')
                        """)
                .param("requestId", requestId)
                .update();
        jdbcClient.sql(
                        """
                        INSERT INTO "authorization".fraud_rule_matches (
                            request_id, match_order, rule_code, severity, evidence,
                            score_contribution
                        ) VALUES (
                            :requestId, 0, 'TEST_REVIEW', 'REVIEW',
                            'Synthetic review evidence', 15
                        )
                        """)
                .param("requestId", requestId)
                .update();
        markCompleted(requestId);
    }

    private void completeDeclinedForReview(UUID requestId) {
        jdbcClient.sql(
                        """
                        INSERT INTO "authorization".authorization_ledger (
                            request_id,
                            decision,
                            decline_reason,
                            fraud_assessment,
                            risk_score,
                            non_fraud_check_result
                        ) VALUES (
                            :requestId,
                            'DECLINED',
                            'FRAUD_REVIEW_REQUIRED',
                            'REVIEW',
                            15,
                            'PASSED'
                        )
                        """)
                .param("requestId", requestId)
                .update();
        jdbcClient.sql(
                        """
                        INSERT INTO "authorization".fraud_rule_matches (
                            request_id, match_order, rule_code, severity, evidence,
                            score_contribution
                        ) VALUES (
                            :requestId, 0, 'TEST_REVIEW', 'REVIEW',
                            'Synthetic review evidence', 15
                        )
                        """)
                .param("requestId", requestId)
                .update();
        markCompleted(requestId);
    }

    private void markCompleted(UUID requestId) {
        jdbcClient.sql(
                        """
                        UPDATE "authorization".authorization_requests
                        SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP
                        WHERE request_id = :requestId
                        """)
                .param("requestId", requestId)
                .update();
    }

    private static Stream<Arguments> conflictingCommands() {
        UUID requestId = UUID.fromString("20000000-0000-4000-8000-000000000005");
        AuthorizationCommand original = standardCommand(requestId);
        return Stream.of(
                Arguments.of(
                        "card token",
                        original,
                        commandWith(original, "tok_D4C3B2A1", original.merchantId(),
                                original.merchantCategoryCode(), original.amount(),
                                original.currency(), original.country(), original.channel(),
                                original.transactionTime())),
                Arguments.of(
                        "merchant",
                        original,
                        commandWith(original, original.cardToken(), "merchant-review",
                                original.merchantCategoryCode(), original.amount(),
                                original.currency(), original.country(), original.channel(),
                                original.transactionTime())),
                Arguments.of(
                        "merchant category code",
                        original,
                        commandWith(original, original.cardToken(), original.merchantId(),
                                "5732", original.amount(), original.currency(), original.country(),
                                original.channel(), original.transactionTime())),
                Arguments.of(
                        "amount",
                        original,
                        commandWith(original, original.cardToken(), original.merchantId(),
                                original.merchantCategoryCode(), new BigDecimal("10.01"),
                                original.currency(), original.country(), original.channel(),
                                original.transactionTime())),
                Arguments.of(
                        "currency",
                        original,
                        commandWith(original, original.cardToken(), original.merchantId(),
                                original.merchantCategoryCode(), original.amount(), "USD",
                                original.country(), original.channel(), original.transactionTime())),
                Arguments.of(
                        "country",
                        original,
                        commandWith(original, original.cardToken(), original.merchantId(),
                                original.merchantCategoryCode(), original.amount(),
                                original.currency(), "FR", original.channel(),
                                original.transactionTime())),
                Arguments.of(
                        "channel",
                        original,
                        commandWith(original, original.cardToken(), original.merchantId(),
                                original.merchantCategoryCode(), original.amount(),
                                original.currency(), original.country(),
                                AuthorizationChannel.POINT_OF_SALE, original.transactionTime())),
                Arguments.of(
                        "transaction time",
                        original,
                        commandWith(original, original.cardToken(), original.merchantId(),
                                original.merchantCategoryCode(), original.amount(),
                                original.currency(), original.country(), original.channel(),
                                original.transactionTime().plusSeconds(1))));
    }

    private static AuthorizationCommand standardCommand(UUID requestId) {
        return command(
                requestId,
                "tok_A1B2C3D4",
                "merchant-standard",
                "5411",
                new BigDecimal("10.0"),
                "EUR",
                "DE",
                AuthorizationChannel.ECOMMERCE,
                TRANSACTION_TIME);
    }

    private static AuthorizationCommand commandWith(
            AuthorizationCommand original,
            String cardToken,
            String merchantId,
            String merchantCategoryCode,
            BigDecimal amount,
            String currency,
            String country,
            AuthorizationChannel channel,
            Instant transactionTime) {
        return command(
                original.requestId(),
                cardToken,
                merchantId,
                merchantCategoryCode,
                amount,
                currency,
                country,
                channel,
                transactionTime);
    }

    private static AuthorizationCommand command(
            UUID requestId,
            String cardToken,
            String merchantId,
            String merchantCategoryCode,
            BigDecimal amount,
            String currency,
            String country,
            AuthorizationChannel channel,
            Instant transactionTime) {
        return new AuthorizationCommand(
                requestId,
                cardToken,
                merchantId,
                merchantCategoryCode,
                amount,
                currency,
                country,
                channel,
                transactionTime);
    }

    private record PersistedRequest(
            String status,
            String fingerprint,
            String requestId,
            String cardToken,
            String merchantId,
            String merchantCategoryCode,
            String amount,
            String currency,
            String country,
            String channel,
            String transactionTime) {}
}
