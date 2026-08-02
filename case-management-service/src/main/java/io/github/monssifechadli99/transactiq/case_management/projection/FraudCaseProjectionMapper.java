package io.github.monssifechadli99.transactiq.case_management.projection;

import com.google.protobuf.Timestamp;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCase;
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudCaseProjectionEvent;
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudCaseProjectionEventType;
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudCaseProjectionSnapshot;
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudRuleEvidence;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.UUID;

public final class FraudCaseProjectionMapper {
    public FraudCaseProjectionEvent map(FraudCase fraudCase, FraudCaseProjectionType type, UUID eventId) {
        FraudCaseProjectionSnapshot.Builder snapshot = FraudCaseProjectionSnapshot.newBuilder()
                .setCaseId(fraudCase.caseId().toString())
                .setRequestId(fraudCase.requestId().toString())
                .setStatus(fraudCase.status().name())
                .setAggregateVersion(fraudCase.version())
                .setAuthorizationOccurredAt(timestamp(fraudCase.occurredAt()))
                .setMerchantId(fraudCase.merchantId())
                .setMerchantCategoryCode(fraudCase.merchantCategoryCode())
                .setAmount(fraudCase.amount().stripTrailingZeros().toPlainString())
                .setCurrency(fraudCase.currency())
                .setCountry(fraudCase.country())
                .setChannel(fraudCase.channel().name())
                .setTransactionTime(timestamp(fraudCase.transactionTime()))
                .setNonFraudResult(fraudCase.nonFraudResult().name())
                .setAuthorizationDecision(fraudCase.authorizationDecision().name())
                .setFraudAssessment(fraudCase.fraudAssessment().name())
                .setRiskScore(fraudCase.riskScore())
                .setCaseRequired(fraudCase.caseRequired())
                .setCreatedAt(timestamp(fraudCase.createdAt()))
                .setUpdatedAt(timestamp(fraudCase.updatedAt()));
        if (fraudCase.assigneeId() != null) snapshot.setAssigneeId(fraudCase.assigneeId());
        if (fraudCase.declineReason() != null) snapshot.setDeclineReason(fraudCase.declineReason().name());
        if (fraudCase.resolutionOutcome() != null) snapshot.setResolutionOutcome(fraudCase.resolutionOutcome().name());
        if (fraudCase.resolutionRationale() != null) snapshot.setResolutionRationale(fraudCase.resolutionRationale());
        if (fraudCase.resolvedBy() != null) snapshot.setResolvedBy(fraudCase.resolvedBy());
        if (fraudCase.resolvedAt() != null) snapshot.setResolvedAt(timestamp(fraudCase.resolvedAt()));
        fraudCase.matchedRules().stream()
                .sorted(Comparator.comparing((io.github.monssifechadli99.transactiq.case_management.domain.FraudRuleSnapshot rule) -> rule.ruleCode())
                        .thenComparing(rule -> rule.severity().name())
                        .thenComparing(rule -> rule.evidence())
                        .thenComparingInt(rule -> rule.scoreContribution()))
                .map(rule -> FraudRuleEvidence.newBuilder()
                        .setRuleCode(rule.ruleCode()).setSeverity(rule.severity().name())
                        .setEvidence(rule.evidence()).setScoreContribution(rule.scoreContribution()).build())
                .forEach(snapshot::addMatchedRules);
        FraudCaseProjectionSnapshot built = snapshot.build();
        return FraudCaseProjectionEvent.newBuilder()
                .setEventId(eventId.toString()).setEventType(eventType(type))
                .setCaseId(fraudCase.caseId().toString()).setAggregateVersion(fraudCase.version())
                .setOccurredAt(timestamp(fraudCase.updatedAt()))
                .setSnapshotHash(hash(built)).setSnapshot(built).build();
    }

    public String hash(FraudCaseProjectionSnapshot snapshot) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(snapshot.toByteArray())); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static FraudCaseProjectionEventType eventType(FraudCaseProjectionType type) {
        return FraudCaseProjectionEventType.valueOf(type.name());
    }
    private static Timestamp timestamp(Instant value) {
        return Timestamp.newBuilder().setSeconds(value.getEpochSecond()).setNanos(value.getNano()).build();
    }
}
