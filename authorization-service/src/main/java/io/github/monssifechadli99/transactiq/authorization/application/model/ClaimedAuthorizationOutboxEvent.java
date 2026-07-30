package io.github.monssifechadli99.transactiq.authorization.application.model;

import java.util.Objects;
import java.util.UUID;

public record ClaimedAuthorizationOutboxEvent(
        UUID eventId,
        UUID leaseToken,
        String partitionKey,
        byte[] payload,
        int attemptCount) {

    public ClaimedAuthorizationOutboxEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");
        if (partitionKey == null || partitionKey.isBlank()) {
            throw new IllegalArgumentException("partitionKey must not be blank");
        }
        Objects.requireNonNull(payload, "payload must not be null");
        if (payload.length == 0) {
            throw new IllegalArgumentException("payload must not be empty");
        }
        if (attemptCount < 0) {
            throw new IllegalArgumentException("attemptCount must not be negative");
        }
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
