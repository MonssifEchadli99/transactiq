package io.github.monssifechadli99.transactiq.authorization.adapter.out.event;

import io.github.monssifechadli99.transactiq.authorization.application.model.ClaimedAuthorizationOutboxEvent;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.AuthorizationOutboxStorePort;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcAuthorizationOutboxStore implements AuthorizationOutboxStorePort {

    private final JdbcClient jdbcClient;
    private final TransactionOperations transactionOperations;

    public JdbcAuthorizationOutboxStore(
            JdbcClient jdbcClient, TransactionOperations transactionOperations) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.transactionOperations = Objects.requireNonNull(
                transactionOperations, "transactionOperations must not be null");
    }

    @Override
    public List<ClaimedAuthorizationOutboxEvent> claimDue(
            int batchSize, Instant now, Duration leaseDuration) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        UUID leaseToken = UUID.randomUUID();
        Instant leaseUntil = now.plus(leaseDuration);

        List<ClaimedAuthorizationOutboxEvent> claimed = transactionOperations.execute(status ->
                jdbcClient.sql(
                                """
                                WITH due AS (
                                    SELECT event_id
                                    FROM "authorization".authorization_outbox
                                    WHERE (
                                        publication_state = 'PENDING'
                                        AND next_attempt_at <= :now
                                    ) OR (
                                        publication_state = 'IN_FLIGHT'
                                        AND lease_until <= :now
                                    )
                                    ORDER BY created_at, event_id
                                    FOR UPDATE SKIP LOCKED
                                    LIMIT :batchSize
                                )
                                UPDATE "authorization".authorization_outbox AS outbox
                                SET publication_state = 'IN_FLIGHT',
                                    lease_token = :leaseToken,
                                    lease_until = :leaseUntil
                                FROM due
                                WHERE outbox.event_id = due.event_id
                                RETURNING outbox.event_id,
                                          outbox.partition_key,
                                          outbox.payload,
                                          outbox.attempt_count
                                """)
                        .param("now", databaseTimestamp(now))
                        .param("batchSize", batchSize)
                        .param("leaseToken", leaseToken)
                        .param("leaseUntil", databaseTimestamp(leaseUntil))
                        .query((resultSet, rowNumber) -> new ClaimedAuthorizationOutboxEvent(
                                resultSet.getObject("event_id", UUID.class),
                                leaseToken,
                                resultSet.getString("partition_key"),
                                resultSet.getBytes("payload"),
                                resultSet.getInt("attempt_count")))
                        .list());
        return claimed == null ? List.of() : List.copyOf(claimed);
    }

    @Override
    public boolean markPublished(UUID eventId, UUID leaseToken, Instant publishedAt) {
        return jdbcClient.sql(
                        """
                        UPDATE "authorization".authorization_outbox
                        SET publication_state = 'PUBLISHED',
                            published_at = :publishedAt,
                            lease_token = NULL,
                            lease_until = NULL,
                            last_error_code = NULL
                        WHERE event_id = :eventId
                          AND publication_state = 'IN_FLIGHT'
                          AND lease_token = :leaseToken
                        """)
                .param("publishedAt", databaseTimestamp(publishedAt))
                .param("eventId", eventId)
                .param("leaseToken", leaseToken)
                .update() == 1;
    }

    @Override
    public boolean markFailed(
            UUID eventId,
            UUID leaseToken,
            Instant nextAttemptAt,
            String errorCode) {
        return jdbcClient.sql(
                        """
                        UPDATE "authorization".authorization_outbox
                        SET publication_state = 'PENDING',
                            attempt_count = attempt_count + 1,
                            next_attempt_at = :nextAttemptAt,
                            lease_token = NULL,
                            lease_until = NULL,
                            last_error_code = :errorCode
                        WHERE event_id = :eventId
                          AND publication_state = 'IN_FLIGHT'
                          AND lease_token = :leaseToken
                        """)
                .param("nextAttemptAt", databaseTimestamp(nextAttemptAt))
                .param("errorCode", errorCode)
                .param("eventId", eventId)
                .param("leaseToken", leaseToken)
                .update() == 1;
    }

    private static OffsetDateTime databaseTimestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
