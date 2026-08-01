package io.github.monssifechadli99.transactiq.case_management.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FraudCase(
        UUID caseId,
        UUID sourceEventId,
        String sourceEventHash,
        UUID requestId,
        FraudCaseStatus status,
        String assigneeId,
        long version,
        Instant occurredAt,
        String cardTokenFingerprint,
        String merchantId,
        String merchantCategoryCode,
        BigDecimal amount,
        String currency,
        String country,
        TransactionChannel channel,
        Instant transactionTime,
        NonFraudResult nonFraudResult,
        AuthorizationDecision authorizationDecision,
        DeclineReason declineReason,
        FraudAssessment fraudAssessment,
        int riskScore,
        boolean caseRequired,
        Instant createdAt,
        Instant updatedAt,
        FraudCaseResolutionOutcome resolutionOutcome,
        String resolutionRationale,
        Instant resolvedAt,
        String resolvedBy,
        List<FraudRuleSnapshot> matchedRules) {

    public FraudCase {
        matchedRules = List.copyOf(matchedRules);
    }
}
