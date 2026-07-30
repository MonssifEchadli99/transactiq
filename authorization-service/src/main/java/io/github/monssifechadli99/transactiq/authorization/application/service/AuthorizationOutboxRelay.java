package io.github.monssifechadli99.transactiq.authorization.application.service;

import io.github.monssifechadli99.transactiq.authorization.application.model.ClaimedAuthorizationOutboxEvent;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.AuthorizationCompletedEventPublisherPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.AuthorizationOutboxStorePort;
import io.github.monssifechadli99.transactiq.authorization.configuration.AuthorizationOutboxPublisherProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class AuthorizationOutboxRelay {

    public static final String PUBLISH_FAILURE_CODE = "KAFKA_PUBLISH_FAILED";
    static final String STALE_LEASE_MESSAGE =
            "Authorization outbox lease is no longer owned for event ";

    private final AuthorizationOutboxStorePort outboxStore;
    private final AuthorizationCompletedEventPublisherPort eventPublisher;
    private final AuthorizationOutboxPublisherProperties properties;
    private final Clock clock;

    public AuthorizationOutboxRelay(
            AuthorizationOutboxStorePort outboxStore,
            AuthorizationCompletedEventPublisherPort eventPublisher,
            AuthorizationOutboxPublisherProperties properties,
            Clock clock) {
        this.outboxStore = Objects.requireNonNull(outboxStore, "outboxStore must not be null");
        this.eventPublisher = Objects.requireNonNull(
                eventPublisher, "eventPublisher must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public void publishDue() {
        Instant claimedAt = clock.instant();
        List<ClaimedAuthorizationOutboxEvent> claimed = outboxStore.claimDue(
                properties.batchSize(), claimedAt, properties.leaseDuration());
        for (ClaimedAuthorizationOutboxEvent event : claimed) {
            publishClaimed(event);
        }
    }

    private void publishClaimed(ClaimedAuthorizationOutboxEvent event) {
        try {
            eventPublisher.publish(event);
        } catch (RuntimeException publicationFailure) {
            Instant nextAttemptAt = clock.instant().plus(retryBackoff(event.attemptCount()));
            requireOwnedLease(
                    outboxStore.markFailed(
                            event.eventId(),
                            event.leaseToken(),
                            nextAttemptAt,
                            PUBLISH_FAILURE_CODE),
                    event);
            return;
        }
        requireOwnedLease(
                outboxStore.markPublished(event.eventId(), event.leaseToken(), clock.instant()),
                event);
    }

    private static void requireOwnedLease(
            boolean stateUpdated, ClaimedAuthorizationOutboxEvent event) {
        if (!stateUpdated) {
            throw new IllegalStateException(STALE_LEASE_MESSAGE + event.eventId());
        }
    }

    Duration retryBackoff(int previousAttemptCount) {
        Duration retry = properties.initialRetryBackoff();
        for (int attempt = 0;
                attempt < previousAttemptCount && retry.compareTo(properties.maxRetryBackoff()) < 0;
                attempt++) {
            if (retry.compareTo(properties.maxRetryBackoff().dividedBy(2)) > 0) {
                retry = properties.maxRetryBackoff();
            } else {
                retry = retry.multipliedBy(2);
            }
        }
        return retry;
    }
}
