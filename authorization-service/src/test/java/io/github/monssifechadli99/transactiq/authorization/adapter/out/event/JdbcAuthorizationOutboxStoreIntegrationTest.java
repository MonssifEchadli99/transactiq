package io.github.monssifechadli99.transactiq.authorization.adapter.out.event;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.authorization.application.model.ClaimedAuthorizationOutboxEvent;
import io.github.monssifechadli99.transactiq.authorization.support.AuthorizationServiceIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AuthorizationServiceIntegrationTest
class JdbcAuthorizationOutboxStoreIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-22T13:00:00Z");
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    private static final String PARTITION_KEY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private final List<UUID> requestIds = new ArrayList<>();

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private JdbcAuthorizationOutboxStore store;

    @BeforeEach
    void createStore() {
        store = new JdbcAuthorizationOutboxStore(
                jdbcClient, new TransactionTemplate(transactionManager));
    }

    @AfterEach
    void removeRows() {
        for (UUID requestId : requestIds) {
            jdbcClient.sql(
                            """
                            DELETE FROM "authorization".authorization_outbox
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
    }

    @Test
    void failedClaimBecomesDueAtBackoffAndSuccessfulRetryBecomesPublished() {
        UUID eventId = UUID.fromString("50000000-0000-4000-8000-000000000005");
        byte[] storedPayload = {10, 20, 30};
        insertPending(eventId, storedPayload, NOW);

        ClaimedAuthorizationOutboxEvent firstClaim = store
                .claimDue(10, NOW, LEASE_DURATION)
                .getFirst();
        Instant nextAttemptAt = NOW.plusSeconds(5);

        assertArrayEquals(storedPayload, firstClaim.payload());
        assertTrue(store.markFailed(
                eventId, firstClaim.leaseToken(), nextAttemptAt, "KAFKA_PUBLISH_FAILED"));
        assertEquals(
                new PersistedPublication("PENDING", 1, nextAttemptAt, null, "KAFKA_PUBLISH_FAILED"),
                publication(eventId));
        assertTrue(store.claimDue(10, nextAttemptAt.minusMillis(1), LEASE_DURATION).isEmpty());

        ClaimedAuthorizationOutboxEvent retry = store
                .claimDue(10, nextAttemptAt, LEASE_DURATION)
                .getFirst();
        assertArrayEquals(storedPayload, retry.payload());
        assertEquals(1, retry.attemptCount());
        assertNotEquals(firstClaim.leaseToken(), retry.leaseToken());
        assertFalse(store.markPublished(eventId, firstClaim.leaseToken(), NOW.plusSeconds(6)));
        assertTrue(store.markPublished(eventId, retry.leaseToken(), NOW.plusSeconds(6)));
        assertEquals(
                new PersistedPublication("PUBLISHED", 1, nextAttemptAt, NOW.plusSeconds(6), null),
                publication(eventId));
        assertTrue(store.claimDue(10, NOW.plusSeconds(60), LEASE_DURATION).isEmpty());
    }

    @Test
    void expiredLeaseIsReclaimedAndSeparateClaimersDoNotClaimSameRows() {
        UUID firstEventId = UUID.fromString("60000000-0000-4000-8000-000000000006");
        UUID secondEventId = UUID.fromString("70000000-0000-4000-8000-000000000007");
        insertPending(firstEventId, new byte[] {1}, NOW);
        insertPending(secondEventId, new byte[] {2}, NOW.plusMillis(1));

        ClaimedAuthorizationOutboxEvent first = store
                .claimDue(1, NOW.plusMillis(1), LEASE_DURATION)
                .getFirst();
        JdbcAuthorizationOutboxStore secondStore = new JdbcAuthorizationOutboxStore(
                jdbcClient, new TransactionTemplate(transactionManager));
        ClaimedAuthorizationOutboxEvent second = secondStore
                .claimDue(1, NOW.plusMillis(1), LEASE_DURATION)
                .getFirst();

        assertNotEquals(first.eventId(), second.eventId());
        assertTrue(store.claimDue(10, NOW.plusSeconds(29), LEASE_DURATION).isEmpty());

        List<ClaimedAuthorizationOutboxEvent> reclaimed =
                store.claimDue(10, NOW.plusSeconds(31), LEASE_DURATION);
        assertEquals(2, reclaimed.size());
        assertEquals(
                List.of(firstEventId, secondEventId),
                reclaimed.stream().map(ClaimedAuthorizationOutboxEvent::eventId).sorted().toList());
        assertTrue(reclaimed.stream().noneMatch(event ->
                event.leaseToken().equals(first.leaseToken())
                        || event.leaseToken().equals(second.leaseToken())));
    }

    private void insertPending(UUID eventId, byte[] payload, Instant dueAt) {
        UUID requestId = UUID.fromString(eventId.toString().replaceFirst("^[0-9]", "8"));
        requestIds.add(requestId);
        jdbcClient.sql(
                        """
                        INSERT INTO "authorization".authorization_requests (
                            request_id, request_fingerprint, request_payload,
                            status, created_at, completed_at
                        ) VALUES (
                            :requestId, :fingerprint, CAST(:payload AS jsonb),
                            'COMPLETED', :createdAt, :completedAt
                        )
                        """)
                .param("requestId", requestId)
                .param("fingerprint", "0".repeat(64))
                .param("payload", "{}")
                .param("createdAt", timestamp(NOW.minusSeconds(1)))
                .param("completedAt", timestamp(NOW))
                .update();
        jdbcClient.sql(
                        """
                        INSERT INTO "authorization".authorization_ledger (
                            request_id, decision, decline_reason, fraud_assessment,
                            non_fraud_check_result, created_at, risk_score
                        ) VALUES (
                            :requestId, 'APPROVED', NULL, 'CLEAR',
                            'PASSED', :createdAt, 0
                        )
                        """)
                .param("requestId", requestId)
                .param("createdAt", timestamp(NOW))
                .update();
        jdbcClient.sql(
                        """
                        INSERT INTO "authorization".authorization_outbox (
                            event_id, request_id, event_type, event_version,
                            partition_key, payload, occurred_at, created_at,
                            publication_state, published_at, attempt_count,
                            next_attempt_at, lease_token, lease_until, last_error_code
                        ) VALUES (
                            :eventId, :requestId, 'AUTHORIZATION_COMPLETED', 1,
                            :partitionKey, :payload, :occurredAt, :createdAt,
                            'PENDING', NULL, 0, :nextAttemptAt, NULL, NULL, NULL
                        )
                        """)
                .param("eventId", eventId)
                .param("requestId", requestId)
                .param("partitionKey", PARTITION_KEY)
                .param("payload", payload)
                .param("occurredAt", timestamp(dueAt))
                .param("createdAt", timestamp(dueAt))
                .param("nextAttemptAt", timestamp(dueAt))
                .update();
    }

    private PersistedPublication publication(UUID eventId) {
        return jdbcClient.sql(
                        """
                        SELECT publication_state, attempt_count, next_attempt_at,
                               published_at, last_error_code
                        FROM "authorization".authorization_outbox
                        WHERE event_id = :eventId
                        """)
                .param("eventId", eventId)
                .query((resultSet, rowNumber) -> new PersistedPublication(
                        resultSet.getString("publication_state"),
                        resultSet.getInt("attempt_count"),
                        resultSet.getObject("next_attempt_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("published_at", OffsetDateTime.class) == null
                                ? null
                                : resultSet.getObject("published_at", OffsetDateTime.class).toInstant(),
                        resultSet.getString("last_error_code")))
                .single();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record PersistedPublication(
            String state,
            int attemptCount,
            Instant nextAttemptAt,
            Instant publishedAt,
            String lastErrorCode) {}
}
