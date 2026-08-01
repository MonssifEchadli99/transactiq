package io.github.monssifechadli99.transactiq.case_management.adapter.out.jdbc;

import static io.github.monssifechadli99.transactiq.case_management.support.AuthorizationEventFixtures.reviewEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka.AuthorizationCompletedEventParser;
import io.github.monssifechadli99.transactiq.case_management.application.model.FraudCaseClaimResult.Outcome;
import io.github.monssifechadli99.transactiq.case_management.application.model.FraudCaseQuery;
import io.github.monssifechadli99.transactiq.case_management.application.model.FraudCaseResolutionResult;
import io.github.monssifechadli99.transactiq.case_management.application.port.out.FraudCaseStore;
import io.github.monssifechadli99.transactiq.case_management.domain.AuthorizationEventSnapshot;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseAssignmentFilter;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseClaimPolicy;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseStatus;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseResolutionPolicy;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseResolutionOutcome;
import io.github.monssifechadli99.transactiq.case_management.support.PostgreSqlTestcontainersConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Import(PostgreSqlTestcontainersConfiguration.class)
class JdbcFraudCaseLifecycleStoreIntegrationTest {
    private static final Instant CREATED = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant CLAIMED = Instant.parse("2026-08-01T11:00:00Z");
    private static final Instant RESOLVED = Instant.parse("2026-08-01T12:00:00Z");

    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private final AuthorizationCompletedEventParser parser = new AuthorizationCompletedEventParser();
    private JdbcFraudCaseLifecycleStore lifecycle;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE fraud_case.fraud_cases CASCADE").update();
        lifecycle = lifecycleStore(CLAIMED);
    }

    @Test
    void queueIsDeterministicSupportsKeysetAndCombinedFilters() {
        UUID first = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID second = UUID.fromString("20000000-0000-4000-8000-000000000001");
        UUID third = UUID.fromString("30000000-0000-4000-8000-000000000001");
        create(first, UUID.randomUUID(), CREATED);
        create(second, UUID.randomUUID(), CREATED);
        create(third, UUID.randomUUID(), CREATED.plusSeconds(1));
        assertEquals(Outcome.CLAIMED, lifecycle.claim(second, "analyst-a", 0).outcome());

        var firstPage = lifecycle.findPage(query(null, FraudCaseAssignmentFilter.ANY, null, 2, null, null));
        assertEquals(List.of(first, second), firstPage.stream().map(row -> row.caseId()).toList());
        var secondPage = lifecycle.findPage(query(null, FraudCaseAssignmentFilter.ANY, null, 2,
                firstPage.getLast().createdAt(), firstPage.getLast().caseId()));
        assertEquals(List.of(third), secondPage.stream().map(row -> row.caseId()).toList());

        assertEquals(List.of(second), lifecycle.findPage(query(
                        FraudCaseStatus.IN_REVIEW, FraudCaseAssignmentFilter.ASSIGNED,
                        null, 10, null, null)).stream().map(row -> row.caseId()).toList());
        assertEquals(List.of(second), lifecycle.findPage(query(
                        null, FraudCaseAssignmentFilter.MINE,
                        "analyst-a", 10, null, null)).stream().map(row -> row.caseId()).toList());
        assertEquals(List.of(first, third), lifecycle.findPage(query(
                        FraudCaseStatus.NEW, FraudCaseAssignmentFilter.UNASSIGNED,
                        null, 10, null, null)).stream().map(row -> row.caseId()).toList());
    }

    @Test
    void detailsContainCompleteSnapshotAndOrderedRules() {
        UUID caseId = UUID.randomUUID();
        AuthorizationEventSnapshot event = create(caseId, UUID.randomUUID(), CREATED);

        var detail = lifecycle.findById(caseId).orElseThrow();

        assertEquals(event.sourceEventId(), detail.sourceEventId());
        assertEquals(event.sourceEventHash(), detail.sourceEventHash());
        assertEquals(event.cardTokenFingerprint(), detail.cardTokenFingerprint());
        assertEquals(event.decision(), detail.authorizationDecision());
        assertEquals(event.fraudAssessment(), detail.fraudAssessment());
        assertEquals(event.matchedRules(), detail.matchedRules());
        assertEquals(0, detail.version());
        assertEquals(detail.createdAt(), detail.updatedAt());
    }

    @Test
    void firstClaimIsAtomicAndSameAnalystRetryIsIdempotent() {
        UUID caseId = UUID.randomUUID();
        create(caseId, UUID.randomUUID(), CREATED);

        var claimed = lifecycle.claim(caseId, "Analyst-A", 0);
        var retry = lifecycle.claim(caseId, "Analyst-A", 0);

        assertEquals(Outcome.CLAIMED, claimed.outcome());
        assertEquals(FraudCaseStatus.IN_REVIEW, claimed.fraudCase().status());
        assertEquals("Analyst-A", claimed.fraudCase().assigneeId());
        assertEquals(1, claimed.fraudCase().version());
        assertEquals(CLAIMED, claimed.fraudCase().updatedAt());
        assertEquals(Outcome.ALREADY_CLAIMED_BY_ANALYST, retry.outcome());
        assertEquals(1, retry.fraudCase().version());
        assertEquals(CLAIMED, retry.fraudCase().updatedAt());
        assertEquals(1, auditCount(caseId));
    }

    @Test
    void concurrentClaimHasOneWinnerOneConflictAndOneAudit() throws Exception {
        UUID caseId = UUID.randomUUID();
        create(caseId, UUID.randomUUID(), CREATED);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> claimTogether(caseId, "analyst-a", ready, start));
            var second = executor.submit(() -> claimTogether(caseId, "analyst-b", ready, start));
            if (!ready.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("claim workers did not become ready");
            }
            start.countDown();
            List<Outcome> outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(Outcome.CLAIMED::equals).count());
            assertEquals(1, outcomes.stream().filter(Outcome.ALREADY_ASSIGNED::equals).count());
        }
        assertEquals(1, auditCount(caseId));
        assertEquals(1, lifecycle.findById(caseId).orElseThrow().version());
    }

    @Test
    void classifiesVersionAssignmentLifecycleAndNotFoundConflicts() {
        UUID stale = UUID.randomUUID();
        create(stale, UUID.randomUUID(), CREATED);
        jdbc.sql("UPDATE fraud_case.fraud_cases SET version = 2 WHERE case_id = :id")
                .param("id", stale).update();
        assertEquals(Outcome.VERSION_CONFLICT, lifecycle.claim(stale, "analyst-a", 0).outcome());

        UUID assigned = UUID.randomUUID();
        create(assigned, UUID.randomUUID(), CREATED);
        lifecycle.claim(assigned, "analyst-a", 0);
        assertEquals(Outcome.ALREADY_ASSIGNED, lifecycle.claim(assigned, "analyst-b", 0).outcome());

        UUID resolved = UUID.randomUUID();
        create(resolved, UUID.randomUUID(), CREATED);
        lifecycle.claim(resolved, "analyst-a", 0);
        lifecycleStore(RESOLVED).resolve(resolved, "analyst-a", 1,
                FraudCaseResolutionOutcome.CONFIRMED_FRAUD, "Synthetic rationale");
        assertEquals(Outcome.NOT_CLAIMABLE, lifecycle.claim(resolved, "analyst-a", 0).outcome());
        assertEquals(Outcome.NOT_FOUND, lifecycle.claim(UUID.randomUUID(), "analyst-a", 0).outcome());
    }

    @Test
    void auditFailureRollsBackMutation() {
        UUID caseId = UUID.randomUUID();
        create(caseId, UUID.randomUUID(), CREATED);
        jdbc.sql("""
                CREATE FUNCTION fraud_case.reject_lifecycle_event() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'synthetic audit failure'; END; $$ LANGUAGE plpgsql
                """).update();
        jdbc.sql("""
                CREATE TRIGGER reject_lifecycle_event BEFORE INSERT
                ON fraud_case.fraud_case_lifecycle_events
                FOR EACH ROW EXECUTE FUNCTION fraud_case.reject_lifecycle_event()
                """).update();
        try {
            assertThrows(RuntimeException.class, () -> lifecycle.claim(caseId, "analyst-a", 0));
            var unchanged = lifecycle.findById(caseId).orElseThrow();
            assertEquals(FraudCaseStatus.NEW, unchanged.status());
            assertNull(unchanged.assigneeId());
            assertEquals(0, unchanged.version());
            assertEquals(unchanged.createdAt(), unchanged.updatedAt());
            assertEquals(0, auditCount(caseId));
        } finally {
            jdbc.sql("DROP TRIGGER reject_lifecycle_event ON fraud_case.fraud_case_lifecycle_events").update();
            jdbc.sql("DROP FUNCTION fraud_case.reject_lifecycle_event()").update();
        }
    }

    @Test
    void duplicateKafkaCreationAfterClaimPreservesLifecycleAndSnapshot() {
        UUID caseId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AuthorizationEventSnapshot event = create(caseId, requestId, CREATED);
        lifecycle.claim(caseId, "analyst-a", 0);
        var before = lifecycle.findById(caseId).orElseThrow();

        assertEquals(FraudCaseStore.CreationResult.ALREADY_EXISTS,
                creationStore(CREATED, UUID::randomUUID).create(event));

        var after = lifecycleStore(Instant.parse("2026-08-02T00:00:00Z"))
                .findById(caseId).orElseThrow();
        assertEquals(before, after);
        assertEquals(1, auditCount(caseId));
    }

    @Test
    void resolutionPersistsOneTimestampExactRetryAndOrderedHistory() {
        UUID caseId = UUID.randomUUID();
        AuthorizationEventSnapshot event = create(caseId, UUID.randomUUID(), CREATED);
        lifecycle.claim(caseId, "analyst-a", 0);
        JdbcFraudCaseLifecycleStore resolving = lifecycleStore(RESOLVED);

        var result = resolving.resolve(caseId, "analyst-a", 1,
                FraudCaseResolutionOutcome.CONFIRMED_FRAUD, "Synthetic rationale");
        var retry = lifecycleStore(RESOLVED.plusSeconds(60)).resolve(
                caseId, "analyst-a", 1,
                FraudCaseResolutionOutcome.CONFIRMED_FRAUD, "Synthetic rationale");

        assertEquals(FraudCaseResolutionResult.Outcome.RESOLVED, result.outcome());
        assertEquals(FraudCaseResolutionResult.Outcome.ALREADY_RESOLVED_IDENTICALLY, retry.outcome());
        var resolved = retry.fraudCase();
        assertEquals(FraudCaseStatus.RESOLVED, resolved.status());
        assertEquals("analyst-a", resolved.assigneeId());
        assertEquals(2, resolved.version());
        assertEquals(FraudCaseResolutionOutcome.CONFIRMED_FRAUD, resolved.resolutionOutcome());
        assertEquals("Synthetic rationale", resolved.resolutionRationale());
        assertEquals("analyst-a", resolved.resolvedBy());
        assertEquals(RESOLVED, resolved.resolvedAt());
        assertEquals(RESOLVED, resolved.updatedAt());
        assertEquals(event.sourceEventHash(), resolved.sourceEventHash());
        assertEquals(event.matchedRules(), resolved.matchedRules());

        var history = resolving.findHistory(caseId).orElseThrow();
        assertEquals(List.of("CLAIMED", "RESOLVED"),
                history.stream().map(item -> item.eventType()).toList());
        assertEquals(List.of(1L, 2L), history.stream().map(item -> item.caseVersion()).toList());
        assertNull(history.getFirst().resolutionOutcome());
        assertEquals(RESOLVED, history.getLast().occurredAt());
        assertEquals(FraudCaseResolutionOutcome.CONFIRMED_FRAUD,
                history.getLast().resolutionOutcome());
        assertEquals(2, auditCount(caseId));
        assertTrue(resolving.findHistory(UUID.randomUUID()).isEmpty());
    }

    @Test
    void resolutionClassifiesNewOwnerVersionAndResolvedConflicts() {
        UUID newCase = UUID.randomUUID();
        create(newCase, UUID.randomUUID(), CREATED);
        assertEquals(resolutionOutcome("NOT_IN_REVIEW"), lifecycle.resolve(
                newCase, "analyst-a", 0, FraudCaseResolutionOutcome.CONFIRMED_FRAUD,
                "Synthetic rationale").outcome());

        UUID owned = UUID.randomUUID();
        create(owned, UUID.randomUUID(), CREATED);
        lifecycle.claim(owned, "analyst-a", 0);
        assertEquals(resolutionOutcome("NOT_ASSIGNED_TO_ANALYST"), lifecycle.resolve(
                owned, "analyst-b", 1, FraudCaseResolutionOutcome.CONFIRMED_FRAUD,
                "Synthetic rationale").outcome());
        assertEquals(resolutionOutcome("VERSION_CONFLICT"), lifecycle.resolve(
                owned, "analyst-a", 0, FraudCaseResolutionOutcome.CONFIRMED_FRAUD,
                "Synthetic rationale").outcome());
        lifecycle.resolve(owned, "analyst-a", 1,
                FraudCaseResolutionOutcome.CONFIRMED_FRAUD, "Synthetic rationale");
        assertEquals(resolutionOutcome("ALREADY_RESOLVED_DIFFERENTLY"), lifecycle.resolve(
                owned, "analyst-a", 1, FraudCaseResolutionOutcome.FALSE_POSITIVE,
                "Synthetic rationale").outcome());
        assertEquals(resolutionOutcome("VERSION_CONFLICT"), lifecycle.resolve(
                owned, "analyst-a", 0, FraudCaseResolutionOutcome.CONFIRMED_FRAUD,
                "Synthetic rationale").outcome());
    }

    @Test
    void concurrentDifferentResolutionsHaveOneWinnerAndOneAudit() throws Exception {
        UUID caseId = UUID.randomUUID();
        create(caseId, UUID.randomUUID(), CREATED);
        lifecycle.claim(caseId, "analyst-a", 0);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> resolveTogether(caseId,
                    FraudCaseResolutionOutcome.CONFIRMED_FRAUD, "Synthetic rationale A",
                    ready, start));
            var second = executor.submit(() -> resolveTogether(caseId,
                    FraudCaseResolutionOutcome.FALSE_POSITIVE, "Synthetic rationale B",
                    ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(value -> value.equals("RESOLVED")).count());
            assertEquals(1, outcomes.stream().filter(
                    value -> value.equals("ALREADY_RESOLVED_DIFFERENTLY")).count());
        }
        assertEquals(2, auditCount(caseId));
    }

    @Test
    void resolutionAuditFailureRollsBackAndDuplicateCreationPreservesResolution() {
        UUID caseId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AuthorizationEventSnapshot event = create(caseId, requestId, CREATED);
        lifecycle.claim(caseId, "analyst-a", 0);
        jdbc.sql("""
                CREATE FUNCTION fraud_case.reject_resolution_event() RETURNS trigger AS $$
                BEGIN IF NEW.event_type = 'RESOLVED' THEN
                    RAISE EXCEPTION 'synthetic resolution audit failure';
                END IF; RETURN NEW; END; $$ LANGUAGE plpgsql
                """).update();
        jdbc.sql("""
                CREATE TRIGGER reject_resolution_event BEFORE INSERT
                ON fraud_case.fraud_case_lifecycle_events
                FOR EACH ROW EXECUTE FUNCTION fraud_case.reject_resolution_event()
                """).update();
        try {
            assertThrows(RuntimeException.class, () -> lifecycleStore(RESOLVED).resolve(
                    caseId, "analyst-a", 1, FraudCaseResolutionOutcome.CONFIRMED_FRAUD,
                    "Synthetic rationale"));
            var unchanged = lifecycle.findById(caseId).orElseThrow();
            assertEquals(FraudCaseStatus.IN_REVIEW, unchanged.status());
            assertEquals(1, unchanged.version());
            assertNull(unchanged.resolutionOutcome());
            assertEquals(1, auditCount(caseId));
        } finally {
            jdbc.sql("DROP TRIGGER reject_resolution_event ON fraud_case.fraud_case_lifecycle_events").update();
            jdbc.sql("DROP FUNCTION fraud_case.reject_resolution_event()").update();
        }

        lifecycleStore(RESOLVED).resolve(caseId, "analyst-a", 1,
                FraudCaseResolutionOutcome.CONFIRMED_FRAUD, "Synthetic rationale");
        var before = lifecycle.findById(caseId).orElseThrow();
        assertEquals(FraudCaseStore.CreationResult.ALREADY_EXISTS,
                creationStore(RESOLVED.plusSeconds(60), UUID::randomUUID).create(event));
        assertEquals(before, lifecycleStore(RESOLVED.plusSeconds(120)).findById(caseId).orElseThrow());
        assertEquals(2, auditCount(caseId));
    }

    private Outcome claimTogether(
            UUID caseId, String analyst, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("claim start was not released");
        }
        return lifecycle.claim(caseId, analyst, 0).outcome();
    }

    private String resolveTogether(
            UUID caseId, FraudCaseResolutionOutcome outcome, String rationale,
            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("resolution start was not released");
        }
        return lifecycleStore(RESOLVED).resolve(caseId, "analyst-a", 1, outcome, rationale)
                .outcome().name();
    }

    private static FraudCaseResolutionResult.Outcome resolutionOutcome(String name) {
        return FraudCaseResolutionResult.Outcome.valueOf(name);
    }

    private AuthorizationEventSnapshot create(UUID caseId, UUID requestId, Instant createdAt) {
        AuthorizationEventSnapshot event = parser.parse(reviewEvent(UUID.randomUUID(), requestId)
                .build().toByteArray());
        assertEquals(FraudCaseStore.CreationResult.CREATED,
                creationStore(createdAt, () -> caseId).create(event));
        return event;
    }

    private JdbcFraudCaseStore creationStore(
            Instant instant, java.util.function.Supplier<UUID> ids) {
        return new JdbcFraudCaseStore(jdbc, new TransactionTemplate(transactionManager),
                Clock.fixed(instant, ZoneOffset.UTC), ids);
    }

    private JdbcFraudCaseLifecycleStore lifecycleStore(Instant instant) {
        return new JdbcFraudCaseLifecycleStore(jdbc, new TransactionTemplate(transactionManager),
                Clock.fixed(instant, ZoneOffset.UTC), UUID::randomUUID,
                new FraudCaseClaimPolicy(), new FraudCaseResolutionPolicy());
    }

    private int auditCount(UUID caseId) {
        return jdbc.sql("""
                SELECT count(*) FROM fraud_case.fraud_case_lifecycle_events
                WHERE fraud_case_id = :caseId
                """).param("caseId", caseId).query(Integer.class).single();
    }

    private static FraudCaseQuery query(
            FraudCaseStatus status, FraudCaseAssignmentFilter assignment, String analyst,
            int size, Instant after, UUID afterId) {
        return new FraudCaseQuery(status, assignment, analyst, size, after, afterId);
    }
}
