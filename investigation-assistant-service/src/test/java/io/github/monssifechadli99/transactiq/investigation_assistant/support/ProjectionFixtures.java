package io.github.monssifechadli99.transactiq.investigation_assistant.support;

import com.google.protobuf.Timestamp;
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudCaseProjectionEvent;
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudCaseProjectionEventType;
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudCaseProjectionSnapshot;
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudRuleEvidence;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.ProjectionValidator;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class ProjectionFixtures {

    private static final ProjectionValidator HASHER = new ProjectionValidator();
    private static final Timestamp FIXED_TIME = Timestamp.newBuilder().setSeconds(1_700_000_000L).build();

    private ProjectionFixtures() {}

    public static FraudCaseProjectionEvent createdEvent(String caseId, long version) {
        return event(caseId, version, "NEW", FraudCaseProjectionEventType.CREATED, null, null);
    }

    public static FraudCaseProjectionEvent claimedEvent(String caseId, long version) {
        return event(caseId, version, "IN_REVIEW", FraudCaseProjectionEventType.CLAIMED, null, null);
    }

    public static FraudCaseProjectionEvent resolvedEvent(
            String caseId, long version, String outcome, String rationale) {
        return event(caseId, version, "RESOLVED", FraudCaseProjectionEventType.RESOLVED, outcome, rationale);
    }

    public static FraudCaseProjectionSnapshot snapshotOf(FraudCaseProjectionEvent event) {
        return event.getSnapshot();
    }

    public static byte[] keyOf(FraudCaseProjectionEvent event) {
        return event.getCaseId().getBytes(StandardCharsets.UTF_8);
    }

    /** Replaces a snapshot and recomputes its independently valid integrity hash. */
    public static FraudCaseProjectionEvent withSnapshot(
            FraudCaseProjectionEvent event, FraudCaseProjectionSnapshot snapshot) {
        return event.toBuilder()
                .setSnapshot(snapshot)
                .setSnapshotHash(HASHER.hash(snapshot))
                .build();
    }

    public static FraudCaseProjectionEvent event(
            String caseId,
            long version,
            String status,
            FraudCaseProjectionEventType type,
            String resolutionOutcome,
            String resolutionRationale) {
        FraudCaseProjectionSnapshot.Builder snapshot = FraudCaseProjectionSnapshot.newBuilder()
                .setCaseId(caseId)
                .setRequestId(UUID.randomUUID().toString())
                .setStatus(status)
                .setAggregateVersion(version)
                .setAuthorizationOccurredAt(FIXED_TIME)
                .setMerchantId("merchant-review")
                .setMerchantCategoryCode("7995")
                .setAmount("125.00")
                .setCurrency("EUR")
                .setCountry("DE")
                .setChannel("ECOMMERCE")
                .setTransactionTime(FIXED_TIME)
                .setNonFraudResult("PASSED")
                .setAuthorizationDecision("DECLINED")
                .setFraudAssessment("HIGH_RISK")
                .setRiskScore(85)
                .setCaseRequired(true)
                .setCreatedAt(FIXED_TIME)
                .setUpdatedAt(FIXED_TIME)
                .addMatchedRules(FraudRuleEvidence.newBuilder()
                        .setRuleCode("VELOCITY").setSeverity("HIGH_RISK")
                        .setEvidence("synthetic rapid repeated authorization attempts")
                        .setScoreContribution(40).build())
                .addMatchedRules(FraudRuleEvidence.newBuilder()
                        .setRuleCode("AMOUNT_ANOMALY").setSeverity("REVIEW")
                        .setEvidence("synthetic amount significantly above merchant baseline")
                        .setScoreContribution(20).build());
        if (!status.equals("NEW")) {
            snapshot.setAssigneeId("analyst-a");
        }
        if (resolutionOutcome != null) {
            snapshot.setResolutionOutcome(resolutionOutcome)
                    .setResolutionRationale(resolutionRationale)
                    .setResolvedBy("analyst-a")
                    .setResolvedAt(FIXED_TIME);
        }
        FraudCaseProjectionSnapshot built = snapshot.build();
        return FraudCaseProjectionEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setEventType(type)
                .setCaseId(caseId)
                .setAggregateVersion(version)
                .setOccurredAt(FIXED_TIME)
                .setSnapshotHash(HASHER.hash(built))
                .setSnapshot(built)
                .build();
    }
}
