package io.github.monssifechadli99.transactiq.investigation_assistant.application;

import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudCaseProjectionEvent;
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudCaseProjectionSnapshot;
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudRuleEvidence;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.InvalidProjectionException;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.ValidatedProjection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Structural and contract validation for the fraud-case projection, scoped to what
 * this module needs to safely derive evidence. Independent from case-search-service's
 * own validator: each consumer group must be able to route malformed input to its own DLT.
 */
public final class ProjectionValidator {

    public FraudCaseProjectionSnapshot validate(byte[] key, FraudCaseProjectionEvent event) {
        return validateProjection(key, event).snapshot();
    }

    public ValidatedProjection validateProjection(byte[] key, FraudCaseProjectionEvent event) {
        if (!event.hasSnapshot()) {
            invalid("snapshot is absent");
        }
        FraudCaseProjectionSnapshot snapshot = event.getSnapshot();
        requireUuid(event.getEventId(), "eventId");
        requireUuid(event.getCaseId(), "caseId");
        requireUuid(snapshot.getCaseId(), "snapshot caseId");
        requireUuid(snapshot.getRequestId(), "requestId");

        String keyCaseId = new String(key, StandardCharsets.UTF_8);
        if (!keyCaseId.equals(event.getCaseId()) || !event.getCaseId().equals(snapshot.getCaseId())) {
            invalid("case identity mismatch");
        }
        if (event.getAggregateVersion() < 0 || event.getAggregateVersion() != snapshot.getAggregateVersion()) {
            invalid("aggregate version mismatch");
        }
        if (!event.getSnapshotHash().matches("[0-9a-f]{64}")) {
            invalid("snapshot hash is malformed");
        }
        if (!hash(snapshot).equals(event.getSnapshotHash())) {
            invalid("snapshot hash mismatch");
        }

        String expectedStatus = switch (event.getEventType()) {
            case CREATED -> "NEW";
            case CLAIMED -> "IN_REVIEW";
            case RESOLVED -> "RESOLVED";
            default -> invalid("event type is unspecified");
        };
        if (!snapshot.getStatus().equals(expectedStatus)) {
            invalid("event type and status are incompatible");
        }
        if (isBlank(snapshot.getCaseId()) || isBlank(snapshot.getRequestId())
                || isBlank(snapshot.getMerchantId())
                || isBlank(snapshot.getMerchantCategoryCode()) || isBlank(snapshot.getAmount())
                || isBlank(snapshot.getCurrency()) || isBlank(snapshot.getCountry())
                || isBlank(snapshot.getChannel()) || isBlank(snapshot.getNonFraudResult())
                || isBlank(snapshot.getAuthorizationDecision())
                || isBlank(snapshot.getFraudAssessment()) || !snapshot.hasAuthorizationOccurredAt()
                || !snapshot.hasTransactionTime() || !snapshot.hasCreatedAt() || !snapshot.hasUpdatedAt()
                || snapshot.getRiskScore() < 0 || snapshot.getRiskScore() > 100) {
            invalid("required field is invalid");
        }

        boolean hasAnyResolutionMetadata = snapshot.hasResolutionOutcome()
                || snapshot.hasResolutionRationale()
                || snapshot.hasResolvedBy()
                || snapshot.hasResolvedAt();
        switch (expectedStatus) {
            case "NEW" -> {
                if (snapshot.hasAssigneeId() || hasAnyResolutionMetadata) {
                    invalid("new snapshot has lifecycle metadata");
                }
            }
            case "IN_REVIEW" -> {
                if (!snapshot.hasAssigneeId() || isBlank(snapshot.getAssigneeId())) {
                    invalid("claimed snapshot lacks assignee");
                }
                if (hasAnyResolutionMetadata) {
                    invalid("claimed snapshot has resolution metadata");
                }
            }
            case "RESOLVED" -> {
                if (!snapshot.hasAssigneeId() || isBlank(snapshot.getAssigneeId())
                        || !snapshot.hasResolutionOutcome() || isBlank(snapshot.getResolutionOutcome())
                        || !snapshot.hasResolutionRationale() || isBlank(snapshot.getResolutionRationale())
                        || !snapshot.hasResolvedBy() || isBlank(snapshot.getResolvedBy())
                        || !snapshot.hasResolvedAt()) {
                    invalid("resolved snapshot is incomplete");
                }
            }
            default -> throw new IllegalStateException("validated status is unsupported");
        }

        if (!java.util.Set.of("PASSED", "INSUFFICIENT_FUNDS")
                .contains(snapshot.getNonFraudResult())
                || !java.util.Set.of("APPROVED", "DECLINED")
                        .contains(snapshot.getAuthorizationDecision())
                || !java.util.Set.of("CLEAR", "REVIEW", "HIGH_RISK")
                        .contains(snapshot.getFraudAssessment())) {
            invalid("business enum is invalid");
        }
        for (FraudRuleEvidence rule : snapshot.getMatchedRulesList()) {
            if (isBlank(rule.getRuleCode()) || isBlank(rule.getSeverity()) || isBlank(rule.getEvidence())
                    || !java.util.Set.of("REVIEW", "HIGH_RISK").contains(rule.getSeverity())) {
                invalid("rule evidence is invalid");
            }
        }
        return new ValidatedProjection(snapshot, event.getSnapshotHash());
    }

    public String hash(FraudCaseProjectionSnapshot snapshot) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(snapshot.toByteArray()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void requireUuid(String value, String field) {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            invalid(field + " is not a UUID");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static <T> T invalid(String message) {
        throw new InvalidProjectionException(message);
    }
}
