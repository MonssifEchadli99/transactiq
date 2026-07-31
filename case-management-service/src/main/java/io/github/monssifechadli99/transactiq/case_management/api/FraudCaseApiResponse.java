package io.github.monssifechadli99.transactiq.case_management.api;

import io.github.monssifechadli99.transactiq.case_management.domain.AuthorizationDecision;
import io.github.monssifechadli99.transactiq.case_management.domain.DeclineReason;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudAssessment;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseStatus;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudRuleSeverity;
import io.github.monssifechadli99.transactiq.case_management.domain.NonFraudResult;
import io.github.monssifechadli99.transactiq.case_management.domain.TransactionChannel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class FraudCaseApiResponse {
    private FraudCaseApiResponse() {}

    public record Page(List<Summary> items, String nextCursor) {
        public Page { items = List.copyOf(items); }
    }

    public record Summary(
            UUID caseId, FraudCaseStatus status, String assigneeId, long version,
            FraudAssessment fraudAssessment, int riskScore,
            AuthorizationDecision authorizationDecision, BigDecimal amount, String currency,
            String merchantId, Instant occurredAt, Instant createdAt, Instant updatedAt) {}

    public record Detail(
            UUID caseId, UUID sourceEventId, String sourceEventHash, UUID requestId,
            FraudCaseStatus status, String assigneeId, long version,
            Instant occurredAt, String cardTokenFingerprint,
            String merchantId, String merchantCategoryCode, BigDecimal amount, String currency,
            String country, TransactionChannel channel, Instant transactionTime,
            NonFraudResult nonFraudResult, AuthorizationDecision authorizationDecision,
            DeclineReason declineReason, FraudAssessment fraudAssessment, int riskScore,
            boolean caseRequired, Instant createdAt, Instant updatedAt,
            List<RuleMatch> matchedRules) {
        public Detail { matchedRules = List.copyOf(matchedRules); }
    }

    public record RuleMatch(
            int matchOrder, String ruleCode, FraudRuleSeverity severity,
            String evidence, int scoreContribution) {}
}
