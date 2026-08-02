package io.github.monssifechadli99.transactiq.case_management.projection;

import java.util.UUID;

public record ClaimedProjectionEvent(UUID eventId, UUID leaseToken, UUID caseId, long aggregateVersion,
        byte[] payload, int attemptCount) {
    public ClaimedProjectionEvent { payload = payload.clone(); }
    @Override public byte[] payload() { return payload.clone(); }
}
