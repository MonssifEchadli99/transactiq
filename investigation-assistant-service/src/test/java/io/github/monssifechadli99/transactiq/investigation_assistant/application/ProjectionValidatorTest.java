package io.github.monssifechadli99.transactiq.investigation_assistant.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudCaseProjectionEvent;
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudCaseProjectionSnapshot;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.InvalidProjectionException;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.ValidatedProjection;
import io.github.monssifechadli99.transactiq.investigation_assistant.support.ProjectionFixtures;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectionValidatorTest {

    private final ProjectionValidator validator = new ProjectionValidator();

    private static byte[] keyOf(FraudCaseProjectionEvent event) {
        return event.getCaseId().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void validCreatedEventReturnsSnapshot() {
        String caseId = UUID.randomUUID().toString();
        FraudCaseProjectionEvent event = ProjectionFixtures.createdEvent(caseId, 0);

        FraudCaseProjectionSnapshot snapshot = validator.validate(keyOf(event), event);

        assertEquals(caseId, snapshot.getCaseId());
    }

    @Test
    void validatedProjectionRetainsPrivateIntegrityWithoutRenderingIt() {
        FraudCaseProjectionEvent event = ProjectionFixtures.createdEvent(UUID.randomUUID().toString(), 0);

        ValidatedProjection projection = validator.validateProjection(keyOf(event), event);

        assertEquals(event.getSnapshotHash(), projection.integrityDiscriminator());
        assertFalse(projection.toString().contains(event.getSnapshotHash()));
    }

    @Test
    void keyAndCaseIdentityMismatchIsRejected() {
        FraudCaseProjectionEvent event = ProjectionFixtures.createdEvent(UUID.randomUUID().toString(), 0);
        byte[] wrongKey = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);

        assertThrows(InvalidProjectionException.class, () -> validator.validate(wrongKey, event));
    }

    @Test
    void tamperedSnapshotHashIsRejected() {
        FraudCaseProjectionEvent event = ProjectionFixtures.createdEvent(UUID.randomUUID().toString(), 0);
        FraudCaseProjectionEvent tampered = event.toBuilder()
                .setSnapshotHash("f".repeat(64))
                .build();

        assertThrows(InvalidProjectionException.class, () -> validator.validate(keyOf(tampered), tampered));
    }

    @Test
    void eventTypeAndStatusMismatchIsRejected() {
        String caseId = UUID.randomUUID().toString();
        FraudCaseProjectionEvent claimed = ProjectionFixtures.claimedEvent(caseId, 1);
        FraudCaseProjectionSnapshot wrongStatus = claimed.getSnapshot().toBuilder().setStatus("NEW").build();
        FraudCaseProjectionEvent event = claimed.toBuilder()
                .setSnapshot(wrongStatus)
                .setSnapshotHash(validator.hash(wrongStatus))
                .build();

        assertThrows(InvalidProjectionException.class, () -> validator.validate(keyOf(event), event));
    }

    @Test
    void resolvedStatusWithoutResolutionFieldsIsRejected() {
        FraudCaseProjectionEvent resolved = ProjectionFixtures.resolvedEvent(
                UUID.randomUUID().toString(), 2, "CONFIRMED_FRAUD", "synthetic rationale");
        FraudCaseProjectionSnapshot incomplete = resolved.getSnapshot().toBuilder()
                .clearResolutionOutcome()
                .clearResolutionRationale()
                .build();
        FraudCaseProjectionEvent event = resolved.toBuilder()
                .setSnapshot(incomplete)
                .setSnapshotHash(validator.hash(incomplete))
                .build();

        assertThrows(InvalidProjectionException.class, () -> validator.validate(keyOf(event), event));
    }

    @Test
    void newStatusWithResolutionMetadataIsRejected() {
        FraudCaseProjectionEvent created = ProjectionFixtures.createdEvent(UUID.randomUUID().toString(), 0);
        FraudCaseProjectionSnapshot malformed = created.getSnapshot().toBuilder()
                .setResolutionOutcome("FALSE_POSITIVE")
                .setResolutionRationale("synthetic lifecycle violation")
                .setResolvedBy("analyst-private")
                .setResolvedAt(created.getOccurredAt())
                .build();
        FraudCaseProjectionEvent event = ProjectionFixtures.withSnapshot(created, malformed);

        assertThrows(InvalidProjectionException.class, () -> validator.validate(keyOf(event), event));
    }

    @Test
    void inReviewStatusWithResolutionMetadataIsRejected() {
        FraudCaseProjectionEvent claimed = ProjectionFixtures.claimedEvent(UUID.randomUUID().toString(), 1);
        FraudCaseProjectionSnapshot malformed = claimed.getSnapshot().toBuilder()
                .setResolutionOutcome("CONFIRMED_FRAUD")
                .setResolutionRationale("synthetic lifecycle violation")
                .setResolvedBy("analyst-private")
                .setResolvedAt(claimed.getOccurredAt())
                .build();
        FraudCaseProjectionEvent event = ProjectionFixtures.withSnapshot(claimed, malformed);

        assertThrows(InvalidProjectionException.class, () -> validator.validate(keyOf(event), event));
    }

    @Test
    void resolvedStatusWithoutResolvedTimestampIsRejected() {
        FraudCaseProjectionEvent resolved = ProjectionFixtures.resolvedEvent(
                UUID.randomUUID().toString(), 2, "CONFIRMED_FRAUD", "synthetic rationale");
        FraudCaseProjectionSnapshot incomplete = resolved.getSnapshot().toBuilder()
                .clearResolvedAt()
                .build();
        FraudCaseProjectionEvent event = ProjectionFixtures.withSnapshot(resolved, incomplete);

        assertThrows(InvalidProjectionException.class, () -> validator.validate(keyOf(event), event));
    }
}
