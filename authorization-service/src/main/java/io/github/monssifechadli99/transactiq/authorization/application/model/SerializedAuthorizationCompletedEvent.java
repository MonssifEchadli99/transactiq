package io.github.monssifechadli99.transactiq.authorization.application.model;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public record SerializedAuthorizationCompletedEvent(
        UUID eventId,
        UUID requestId,
        Instant occurredAt,
        String partitionKey,
        byte[] payload) {

    public SerializedAuthorizationCompletedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (partitionKey == null || partitionKey.isBlank()) {
            throw new IllegalArgumentException("partitionKey must not be blank");
        }
        Objects.requireNonNull(payload, "payload must not be null");
        if (payload.length == 0) {
            throw new IllegalArgumentException("payload must not be empty");
        }
        payload = Arrays.copyOf(payload, payload.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
