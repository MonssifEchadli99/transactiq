package io.github.monssifechadli99.transactiq.case_management.adapter.out.jdbc;

import static io.github.monssifechadli99.transactiq.case_management.support.AuthorizationEventFixtures.highRiskEvent;
import static io.github.monssifechadli99.transactiq.case_management.support.AuthorizationEventFixtures.insufficientFundsReviewEvent;
import static io.github.monssifechadli99.transactiq.case_management.support.AuthorizationEventFixtures.reviewEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka.AuthorizationCompletedEventParser;
import io.github.monssifechadli99.transactiq.case_management.application.port.out.FraudCaseStore.CreationResult;
import io.github.monssifechadli99.transactiq.case_management.domain.AuthorizationEventConflictException;
import io.github.monssifechadli99.transactiq.case_management.domain.AuthorizationEventSnapshot;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudRuleSnapshot;
import io.github.monssifechadli99.transactiq.case_management.projection.FraudCaseProjectionMapper;
import io.github.monssifechadli99.transactiq.case_management.projection.FraudCaseProjectionOutbox;
import io.github.monssifechadli99.transactiq.case_management.support.PostgreSqlTestcontainersConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Import(PostgreSqlTestcontainersConfiguration.class)
class JdbcFraudCaseStoreIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-30T10:16:00Z");

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final AuthorizationCompletedEventParser parser =
            new AuthorizationCompletedEventParser();

    private JdbcFraudCaseStore store;

    @BeforeEach
    void resetDatabase() {
        jdbcClient.sql("TRUNCATE fraud_case.fraud_cases CASCADE").update();
        store = new JdbcFraudCaseStore(
                jdbcClient,
                new TransactionTemplate(transactionManager),
                Clock.fixed(CREATED_AT, ZoneOffset.UTC),
                UUID::randomUUID,
                new FraudCaseProjectionOutbox(jdbcClient, new FraudCaseProjectionMapper(), UUID::randomUUID));
    }

    @Test
    void approvedReviewCreatesNewUnassignedCaseWithCompleteOrderedSnapshot() {
        UUID eventId = UUID.fromString("30000000-0000-4000-8000-000000000001");
        UUID requestId = UUID.fromString("40000000-0000-4000-8000-000000000001");
        AuthorizationEventSnapshot event = parsed(reviewEvent(eventId, requestId)
                .build()
                .toByteArray());

        assertEquals(CreationResult.CREATED, store.create(event));

        PersistedCase persisted = persistedCase(requestId);
        assertEquals(eventId, persisted.sourceEventId());
        assertEquals(event.sourceEventHash(), persisted.sourceEventHash());
        assertEquals("NEW", persisted.status());
        assertNull(persisted.assigneeId());
        assertEquals("APPROVED", persisted.decision());
        assertNull(persisted.declineReason());
        assertEquals("REVIEW", persisted.assessment());
        assertEquals(15, persisted.riskScore());
        assertEquals(event.cardTokenFingerprint(), persisted.cardTokenFingerprint());
        assertEquals(event.merchantId(), persisted.merchantId());
        assertEquals(event.merchantCategoryCode(), persisted.merchantCategoryCode());
        assertEquals(0, event.amount().compareTo(persisted.amount()));
        assertEquals(event.currency(), persisted.currency());
        assertEquals(event.country(), persisted.country());
        assertEquals(event.channel().name(), persisted.channel());
        assertEquals(event.nonFraudResult().name(), persisted.nonFraudResult());
        assertEquals(event.caseRequired(), persisted.caseRequired());
        assertEquals(event.occurredAt(), persisted.occurredAt());
        assertEquals(event.transactionTime(), persisted.transactionTime());
        assertEquals(CREATED_AT, persisted.createdAt());
        assertEquals(1, projectionCount(persisted.caseId()));
        assertEquals("CREATED", projectionType(persisted.caseId(), 0));
        assertEquals(
                List.of(new PersistedRule(
                        0,
                        "MERCHANT_PROFILE",
                        "REVIEW",
                        "Synthetic merchant profile requires review",
                        15)),
                persistedRules(persisted.caseId()));
    }

    @Test
    void declinedHighRiskAndInsufficientFundsReviewBothCreateCases() {
        AuthorizationEventSnapshot highRisk = parsed(highRiskEvent(
                        UUID.randomUUID(), UUID.randomUUID())
                .build()
                .toByteArray());
        AuthorizationEventSnapshot insufficient = parsed(insufficientFundsReviewEvent(
                        UUID.randomUUID(), UUID.randomUUID())
                .build()
                .toByteArray());

        assertEquals(CreationResult.CREATED, store.create(highRisk));
        assertEquals(CreationResult.CREATED, store.create(insufficient));

        assertEquals(2, caseCount());
        PersistedCase insufficientCase = persistedCase(insufficient.requestId());
        assertEquals("DECLINED", insufficientCase.decision());
        assertEquals("INSUFFICIENT_FUNDS", insufficientCase.declineReason());
        assertEquals("REVIEW", insufficientCase.assessment());
        assertEquals(1, persistedRules(insufficientCase.caseId()).size());
    }

    @Test
    void sequentialDuplicateIsSuccessfulNoOp() {
        AuthorizationEventSnapshot event = parsed(reviewEvent(
                        UUID.randomUUID(), UUID.randomUUID())
                .build()
                .toByteArray());

        assertEquals(CreationResult.CREATED, store.create(event));
        assertEquals(CreationResult.ALREADY_EXISTS, store.create(event));

        assertEquals(1, caseCount());
        assertEquals(1, ruleCount());
        assertEquals(1, projectionCount(persistedCase(event.requestId()).caseId()));
    }

    private int projectionCount(UUID caseId) {
        return jdbcClient.sql("SELECT count(*) FROM fraud_case.fraud_case_projection_outbox WHERE fraud_case_id=:id")
                .param("id",caseId).query(Integer.class).single();
    }

    private String projectionType(UUID caseId,long version) {
        return jdbcClient.sql("SELECT event_type FROM fraud_case.fraud_case_projection_outbox WHERE fraud_case_id=:id AND aggregate_version=:version")
                .param("id",caseId).param("version",version).query(String.class).single();
    }

    @Test
    void concurrentDuplicateCreatesExactlyOneCase() throws Exception {
        AuthorizationEventSnapshot event = parsed(reviewEvent(
                        UUID.randomUUID(), UUID.randomUUID())
                .build()
                .toByteArray());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<CreationResult> task = () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return store.create(event);
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(task);
            var second = executor.submit(task);
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            assertEquals(
                    List.of(CreationResult.CREATED, CreationResult.ALREADY_EXISTS),
                    List.of(first.get(), second.get()).stream().sorted().toList());
        }
        assertEquals(1, caseCount());
        assertEquals(1, ruleCount());
    }

    @Test
    void sameEventIdWithDifferentExactByteHashIsContractConflict() {
        UUID eventId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AuthorizationEventSnapshot original = parsed(reviewEvent(eventId, requestId)
                .build()
                .toByteArray());
        store.create(original);
        AuthorizationEventSnapshot changed = parsed(reviewEvent(eventId, requestId)
                .setMerchantId("merchant-review-changed")
                .build()
                .toByteArray());

        assertThrows(AuthorizationEventConflictException.class, () -> store.create(changed));

        assertEquals(1, caseCount());
    }

    @Test
    void sameRequestIdWithDifferentEventIdIsContractConflict() {
        UUID requestId = UUID.randomUUID();
        AuthorizationEventSnapshot original = parsed(reviewEvent(UUID.randomUUID(), requestId)
                .build()
                .toByteArray());
        AuthorizationEventSnapshot conflicting = parsed(reviewEvent(UUID.randomUUID(), requestId)
                .setMerchantId("merchant-review-two")
                .build()
                .toByteArray());
        store.create(original);

        assertThrows(
                AuthorizationEventConflictException.class,
                () -> store.create(conflicting));

        assertEquals(1, caseCount());
    }

    @Test
    void distinctEventsCreateDistinctCases() {
        AuthorizationEventSnapshot first = parsed(reviewEvent(
                        UUID.randomUUID(), UUID.randomUUID())
                .build()
                .toByteArray());
        AuthorizationEventSnapshot second = parsed(highRiskEvent(
                        UUID.randomUUID(), UUID.randomUUID())
                .build()
                .toByteArray());

        store.create(first);
        store.create(second);

        assertEquals(2, caseCount());
    }

    @Test
    void childInsertFailureRollsBackCaseAndEveryRule() {
        AuthorizationEventSnapshot valid = parsed(reviewEvent(
                        UUID.randomUUID(), UUID.randomUUID())
                .build()
                .toByteArray());
        List<FraudRuleSnapshot> invalidRules = new ArrayList<>(valid.matchedRules());
        invalidRules.set(
                0,
                new FraudRuleSnapshot(
                        "R".repeat(129),
                        invalidRules.getFirst().severity(),
                        invalidRules.getFirst().evidence(),
                        invalidRules.getFirst().scoreContribution()));
        AuthorizationEventSnapshot invalid = copy(
                valid,
                valid.sourceEventId(),
                valid.requestId(),
                valid.sourceEventHash(),
                invalidRules);

        assertThrows(DataIntegrityViolationException.class, () -> store.create(invalid));

        assertEquals(0, caseCount());
        assertEquals(0, ruleCount());
    }

    @Test
    void projectionOutboxInsertFailureRollsBackCreationAndRules() {
        AuthorizationEventSnapshot event = parsed(reviewEvent(UUID.randomUUID(), UUID.randomUUID())
                .build().toByteArray());
        jdbcClient.sql("""
                CREATE FUNCTION fraud_case.reject_created_projection() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'synthetic projection failure'; END; $$ LANGUAGE plpgsql
                """).update();
        jdbcClient.sql("""
                CREATE TRIGGER reject_created_projection BEFORE INSERT
                ON fraud_case.fraud_case_projection_outbox
                FOR EACH ROW EXECUTE FUNCTION fraud_case.reject_created_projection()
                """).update();
        try {
            assertThrows(RuntimeException.class, () -> store.create(event));
            assertEquals(0, caseCount());
            assertEquals(0, ruleCount());
            assertEquals(0, jdbcClient.sql("SELECT count(*) FROM fraud_case.fraud_case_projection_outbox")
                    .query(Integer.class).single());
        } finally {
            jdbcClient.sql("DROP TRIGGER reject_created_projection ON fraud_case.fraud_case_projection_outbox").update();
            jdbcClient.sql("DROP FUNCTION fraud_case.reject_created_projection()").update();
        }
    }

    private AuthorizationEventSnapshot parsed(byte[] bytes) {
        return parser.parse(bytes);
    }

    private PersistedCase persistedCase(UUID requestId) {
        return jdbcClient.sql(
                        """
                        SELECT case_id,
                               source_event_id,
                               source_event_hash,
                               status,
                               assignee_id,
                               card_token_fingerprint,
                               merchant_id,
                               merchant_category_code,
                               amount,
                               currency,
                               country,
                               channel,
                               non_fraud_result,
                               authorization_decision,
                               decline_reason,
                               fraud_assessment,
                               risk_score,
                               case_required,
                               occurred_at,
                               occurred_at_nanos,
                               transaction_time,
                               transaction_time_nanos,
                               created_at
                        FROM fraud_case.fraud_cases
                        WHERE request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query((resultSet, rowNumber) -> new PersistedCase(
                        resultSet.getObject("case_id", UUID.class),
                        resultSet.getObject("source_event_id", UUID.class),
                        resultSet.getString("source_event_hash"),
                        resultSet.getString("status"),
                        resultSet.getString("assignee_id"),
                        resultSet.getString("card_token_fingerprint"),
                        resultSet.getString("merchant_id"),
                        resultSet.getString("merchant_category_code"),
                        resultSet.getBigDecimal("amount"),
                        resultSet.getString("currency"),
                        resultSet.getString("country"),
                        resultSet.getString("channel"),
                        resultSet.getString("non_fraud_result"),
                        resultSet.getString("authorization_decision"),
                        resultSet.getString("decline_reason"),
                        resultSet.getString("fraud_assessment"),
                        resultSet.getInt("risk_score"),
                        resultSet.getBoolean("case_required"),
                        exactInstant(
                                resultSet.getObject("occurred_at", java.time.OffsetDateTime.class).toInstant(),
                                resultSet.getInt("occurred_at_nanos")),
                        exactInstant(
                                resultSet.getObject("transaction_time", java.time.OffsetDateTime.class).toInstant(),
                                resultSet.getInt("transaction_time_nanos")),
                        resultSet.getObject("created_at", java.time.OffsetDateTime.class).toInstant()))
                .single();
    }

    private List<PersistedRule> persistedRules(UUID caseId) {
        return jdbcClient.sql(
                        """
                        SELECT match_order, rule_code, severity, evidence, score_contribution
                        FROM fraud_case.fraud_case_rule_matches
                        WHERE case_id = :caseId
                        ORDER BY match_order
                        """)
                .param("caseId", caseId)
                .query((resultSet, rowNumber) -> new PersistedRule(
                        resultSet.getInt("match_order"),
                        resultSet.getString("rule_code"),
                        resultSet.getString("severity"),
                        resultSet.getString("evidence"),
                        resultSet.getInt("score_contribution")))
                .list();
    }

    private int caseCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM fraud_case.fraud_cases")
                .query(Integer.class)
                .single();
    }

    private int ruleCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM fraud_case.fraud_case_rule_matches")
                .query(Integer.class)
                .single();
    }

    private static AuthorizationEventSnapshot copy(
            AuthorizationEventSnapshot source,
            UUID eventId,
            UUID requestId,
            String hash,
            List<FraudRuleSnapshot> rules) {
        return new AuthorizationEventSnapshot(
                eventId,
                hash,
                source.occurredAt(),
                requestId,
                source.cardTokenFingerprint(),
                source.merchantId(),
                source.merchantCategoryCode(),
                source.amount(),
                source.currency(),
                source.country(),
                source.channel(),
                source.transactionTime(),
                source.nonFraudResult(),
                source.decision(),
                source.declineReason(),
                source.fraudAssessment(),
                source.riskScore(),
                rules,
                source.caseRequired());
    }

    private static Instant exactInstant(Instant databaseTimestamp, int nanos) {
        return Instant.ofEpochSecond(databaseTimestamp.getEpochSecond(), nanos);
    }

    private record PersistedCase(
            UUID caseId,
            UUID sourceEventId,
            String sourceEventHash,
            String status,
            String assigneeId,
            String cardTokenFingerprint,
            String merchantId,
            String merchantCategoryCode,
            BigDecimal amount,
            String currency,
            String country,
            String channel,
            String nonFraudResult,
            String decision,
            String declineReason,
            String assessment,
            int riskScore,
            boolean caseRequired,
            Instant occurredAt,
            Instant transactionTime,
            Instant createdAt) {}

    private record PersistedRule(
            int matchOrder,
            String ruleCode,
            String severity,
            String evidence,
            int scoreContribution) {}
}
