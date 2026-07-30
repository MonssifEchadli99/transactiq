package io.github.monssifechadli99.transactiq.case_management.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AuthorizationEventSnapshot(
        UUID sourceEventId,
        String sourceEventHash,
        Instant occurredAt,
        UUID requestId,
        String cardTokenFingerprint,
        String merchantId,
        String merchantCategoryCode,
        BigDecimal amount,
        String currency,
        String country,
        TransactionChannel channel,
        Instant transactionTime,
        NonFraudResult nonFraudResult,
        AuthorizationDecision decision,
        DeclineReason declineReason,
        FraudAssessment fraudAssessment,
        int riskScore,
        List<FraudRuleSnapshot> matchedRules,
        boolean caseRequired) {

    public AuthorizationEventSnapshot {
        Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
        requireText(sourceEventHash, "sourceEventHash");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        requireText(cardTokenFingerprint, "cardTokenFingerprint");
        requireText(merchantId, "merchantId");
        requireText(merchantCategoryCode, "merchantCategoryCode");
        Objects.requireNonNull(amount, "amount must not be null");
        requireText(currency, "currency");
        requireText(country, "country");
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(transactionTime, "transactionTime must not be null");
        Objects.requireNonNull(nonFraudResult, "nonFraudResult must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(fraudAssessment, "fraudAssessment must not be null");
        matchedRules = List.copyOf(
                Objects.requireNonNull(matchedRules, "matchedRules must not be null"));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidAuthorizationEventException(field + " must not be blank");
        }
    }
}
