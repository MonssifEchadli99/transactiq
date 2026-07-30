package io.github.monssifechadli99.transactiq.authorization.application.port.out;

import io.github.monssifechadli99.transactiq.authorization.application.model.ClaimedAuthorizationOutboxEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuthorizationOutboxStorePort {

    List<ClaimedAuthorizationOutboxEvent> claimDue(
            int batchSize, Instant now, Duration leaseDuration);

    boolean markPublished(UUID eventId, UUID leaseToken, Instant publishedAt);

    boolean markFailed(
            UUID eventId,
            UUID leaseToken,
            Instant nextAttemptAt,
            String errorCode);
}
