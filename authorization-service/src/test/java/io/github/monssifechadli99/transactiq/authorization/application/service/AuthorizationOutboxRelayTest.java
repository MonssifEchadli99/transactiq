package io.github.monssifechadli99.transactiq.authorization.application.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.monssifechadli99.transactiq.authorization.application.model.ClaimedAuthorizationOutboxEvent;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.AuthorizationCompletedEventPublisherPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.AuthorizationOutboxStorePort;
import io.github.monssifechadli99.transactiq.authorization.configuration.AuthorizationOutboxPublisherProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthorizationOutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-07-22T12:00:00Z");
    private static final UUID EVENT_ID =
            UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final UUID LEASE_TOKEN =
            UUID.fromString("40000000-0000-4000-8000-000000000004");

    @Test
    void marksPublishedOnlyAfterPublisherReturnsSuccessfully() {
        byte[] storedPayload = {1, 2, 3, 4};
        RecordingStore store = new RecordingStore(claim(storedPayload, 0));
        RecordingPublisher publisher = new RecordingPublisher(false);

        new AuthorizationOutboxRelay(store, publisher, properties(), fixedClock()).publishDue();

        assertArrayEquals(storedPayload, publisher.publishedPayload);
        assertEquals(EVENT_ID, store.publishedEventId);
        assertEquals(LEASE_TOKEN, store.publishedLeaseToken);
        assertEquals(NOW, store.publishedAt);
        assertNull(store.failedEventId);
    }

    @Test
    void recordsStableFailureCodeAndInitialBackoffWithoutThrowing() {
        RecordingStore store = new RecordingStore(claim(new byte[] {9}, 0));

        new AuthorizationOutboxRelay(
                        store,
                        new RecordingPublisher(true),
                        properties(),
                        fixedClock())
                .publishDue();

        assertEquals(EVENT_ID, store.failedEventId);
        assertEquals(LEASE_TOKEN, store.failedLeaseToken);
        assertEquals(NOW.plusSeconds(1), store.nextAttemptAt);
        assertEquals(AuthorizationOutboxRelay.PUBLISH_FAILURE_CODE, store.errorCode);
        assertNull(store.publishedEventId);
    }

    @Test
    void surfacesStaleLeaseWhenPublishedStateCannotBeRecorded() {
        RecordingStore store =
                new RecordingStore(claim(new byte[] {1}, 0), false, true);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new AuthorizationOutboxRelay(
                                store,
                                new RecordingPublisher(false),
                                properties(),
                                fixedClock())
                        .publishDue());

        assertEquals(
                AuthorizationOutboxRelay.STALE_LEASE_MESSAGE + EVENT_ID,
                failure.getMessage());
        assertEquals(EVENT_ID, store.publishedEventId);
        assertNull(store.failedEventId);
    }

    @Test
    void surfacesStaleLeaseWhenFailedStateCannotBeRecorded() {
        RecordingStore store =
                new RecordingStore(claim(new byte[] {1}, 0), true, false);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new AuthorizationOutboxRelay(
                                store,
                                new RecordingPublisher(true),
                                properties(),
                                fixedClock())
                        .publishDue());

        assertEquals(
                AuthorizationOutboxRelay.STALE_LEASE_MESSAGE + EVENT_ID,
                failure.getMessage());
        assertEquals(EVENT_ID, store.failedEventId);
        assertNull(store.publishedEventId);
    }

    @Test
    void exponentialBackoffIsBounded() {
        AuthorizationOutboxRelay relay = new AuthorizationOutboxRelay(
                new RecordingStore(List.of()),
                new RecordingPublisher(false),
                properties(),
                fixedClock());

        assertEquals(Duration.ofSeconds(1), relay.retryBackoff(0));
        assertEquals(Duration.ofSeconds(2), relay.retryBackoff(1));
        assertEquals(Duration.ofSeconds(8), relay.retryBackoff(3));
        assertEquals(Duration.ofSeconds(10), relay.retryBackoff(20));
    }

    private static List<ClaimedAuthorizationOutboxEvent> claim(byte[] payload, int attemptCount) {
        return List.of(new ClaimedAuthorizationOutboxEvent(
                EVENT_ID,
                LEASE_TOKEN,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                payload,
                attemptCount));
    }

    private static AuthorizationOutboxPublisherProperties properties() {
        return new AuthorizationOutboxPublisherProperties(
                true,
                "transactiq.authorization.completed.v1",
                50,
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofSeconds(10));
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static final class RecordingPublisher implements AuthorizationCompletedEventPublisherPort {

        private final boolean fail;
        private byte[] publishedPayload;

        private RecordingPublisher(boolean fail) {
            this.fail = fail;
        }

        @Override
        public void publish(ClaimedAuthorizationOutboxEvent event) {
            if (fail) {
                throw new IllegalStateException("synthetic broker unavailable");
            }
            publishedPayload = event.payload();
        }
    }

    private static final class RecordingStore implements AuthorizationOutboxStorePort {

        private final List<ClaimedAuthorizationOutboxEvent> claimed;
        private UUID publishedEventId;
        private UUID publishedLeaseToken;
        private Instant publishedAt;
        private UUID failedEventId;
        private UUID failedLeaseToken;
        private Instant nextAttemptAt;
        private String errorCode;
        private final boolean markPublishedResult;
        private final boolean markFailedResult;

        private RecordingStore(List<ClaimedAuthorizationOutboxEvent> claimed) {
            this(claimed, true, true);
        }

        private RecordingStore(
                List<ClaimedAuthorizationOutboxEvent> claimed,
                boolean markPublishedResult,
                boolean markFailedResult) {
            this.claimed = claimed;
            this.markPublishedResult = markPublishedResult;
            this.markFailedResult = markFailedResult;
        }

        @Override
        public List<ClaimedAuthorizationOutboxEvent> claimDue(
                int batchSize, Instant now, Duration leaseDuration) {
            assertEquals(50, batchSize);
            assertEquals(NOW, now);
            assertEquals(Duration.ofSeconds(30), leaseDuration);
            return claimed;
        }

        @Override
        public boolean markPublished(UUID eventId, UUID leaseToken, Instant publishedAt) {
            this.publishedEventId = eventId;
            this.publishedLeaseToken = leaseToken;
            this.publishedAt = publishedAt;
            return markPublishedResult;
        }

        @Override
        public boolean markFailed(
                UUID eventId,
                UUID leaseToken,
                Instant nextAttemptAt,
                String errorCode) {
            this.failedEventId = eventId;
            this.failedLeaseToken = leaseToken;
            this.nextAttemptAt = nextAttemptAt;
            this.errorCode = errorCode;
            return markFailedResult;
        }
    }
}
