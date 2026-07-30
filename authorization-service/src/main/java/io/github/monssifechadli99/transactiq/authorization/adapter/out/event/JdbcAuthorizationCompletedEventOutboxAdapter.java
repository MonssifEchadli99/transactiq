package io.github.monssifechadli99.transactiq.authorization.adapter.out.event;

import io.github.monssifechadli99.transactiq.authorization.application.model.SerializedAuthorizationCompletedEvent;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.AuthorizationCompletedEventOutboxPort;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationOutcome;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessmentResult;
import io.github.monssifechadli99.transactiq.authorization.domain.NonFraudCheckResult;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcAuthorizationCompletedEventOutboxAdapter
        implements AuthorizationCompletedEventOutboxPort {

    public static final String EVENT_TYPE = "AUTHORIZATION_COMPLETED";
    public static final int EVENT_VERSION = 1;

    private final JdbcClient jdbcClient;
    private final AuthorizationCompletedEventMapper eventMapper;

    public JdbcAuthorizationCompletedEventOutboxAdapter(
            JdbcClient jdbcClient, AuthorizationCompletedEventMapper eventMapper) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper must not be null");
    }

    @Override
    public void append(
            AuthorizationCommand command,
            FraudAssessmentResult fraudAssessment,
            NonFraudCheckResult nonFraudCheckResult,
            AuthorizationOutcome outcome) {
        SerializedAuthorizationCompletedEvent event = eventMapper.map(
                command, fraudAssessment, nonFraudCheckResult, outcome);

        int inserted = jdbcClient.sql(
                        """
                        INSERT INTO "authorization".authorization_outbox (
                            event_id,
                            request_id,
                            event_type,
                            event_version,
                            partition_key,
                            payload,
                            occurred_at,
                            created_at,
                            publication_state,
                            published_at,
                            attempt_count,
                            next_attempt_at,
                            lease_token,
                            lease_until,
                            last_error_code
                        ) VALUES (
                            :eventId,
                            :requestId,
                            :eventType,
                            :eventVersion,
                            :partitionKey,
                            :payload,
                            :occurredAt,
                            :createdAt,
                            'PENDING',
                            NULL,
                            0,
                            :nextAttemptAt,
                            NULL,
                            NULL,
                            NULL
                        )
                        """)
                .param("eventId", event.eventId())
                .param("requestId", event.requestId())
                .param("eventType", EVENT_TYPE)
                .param("eventVersion", EVENT_VERSION)
                .param("partitionKey", event.partitionKey())
                .param("payload", event.payload())
                .param("occurredAt", OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
                .param("createdAt", OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
                .param("nextAttemptAt", OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
                .update();
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Expected one row to insert authorization-completed outbox event");
        }
    }
}
