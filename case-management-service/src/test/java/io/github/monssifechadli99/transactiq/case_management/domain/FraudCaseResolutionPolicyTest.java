package io.github.monssifechadli99.transactiq.case_management.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FraudCaseResolutionPolicyTest {
    private final FraudCaseResolutionPolicy policy = new FraudCaseResolutionPolicy();

    @Test
    void classifiesEligibilityOwnershipVersionAndExactRetry() {
        assertDecision(FraudCaseResolutionPolicy.Decision.RESOLVABLE,
                caseWith(FraudCaseStatus.IN_REVIEW, "analyst-a", 1, null, null, null),
                "analyst-a", 1, FraudCaseResolutionOutcome.CONFIRMED_FRAUD, "Synthetic reason");
        assertDecision(FraudCaseResolutionPolicy.Decision.NOT_IN_REVIEW,
                caseWith(FraudCaseStatus.NEW, null, 0, null, null, null),
                "analyst-a", 0, FraudCaseResolutionOutcome.CONFIRMED_FRAUD, "Synthetic reason");
        assertDecision(FraudCaseResolutionPolicy.Decision.NOT_ASSIGNED_TO_ANALYST,
                caseWith(FraudCaseStatus.IN_REVIEW, "analyst-b", 1, null, null, null),
                "analyst-a", 1, FraudCaseResolutionOutcome.CONFIRMED_FRAUD, "Synthetic reason");
        assertDecision(FraudCaseResolutionPolicy.Decision.VERSION_CONFLICT,
                caseWith(FraudCaseStatus.IN_REVIEW, "analyst-a", 2, null, null, null),
                "analyst-a", 1, FraudCaseResolutionOutcome.CONFIRMED_FRAUD, "Synthetic reason");
        var resolved = caseWith(FraudCaseStatus.RESOLVED, "analyst-a", 2,
                "analyst-a", FraudCaseResolutionOutcome.CONFIRMED_FRAUD, "Synthetic reason");
        assertDecision(FraudCaseResolutionPolicy.Decision.ALREADY_RESOLVED_IDENTICALLY,
                resolved, "analyst-a", 1,
                FraudCaseResolutionOutcome.CONFIRMED_FRAUD, "Synthetic reason");
        assertDecision(FraudCaseResolutionPolicy.Decision.VERSION_CONFLICT,
                resolved, "analyst-a", 0,
                FraudCaseResolutionOutcome.CONFIRMED_FRAUD, "Synthetic reason");
        assertDecision(FraudCaseResolutionPolicy.Decision.ALREADY_RESOLVED_DIFFERENTLY,
                resolved, "analyst-a", 1,
                FraudCaseResolutionOutcome.FALSE_POSITIVE, "Synthetic reason");
    }

    private void assertDecision(
            FraudCaseResolutionPolicy.Decision expected, FraudCase fraudCase,
            String analyst, long version, FraudCaseResolutionOutcome outcome, String rationale) {
        assertEquals(expected, policy.classify(fraudCase, analyst, version, outcome, rationale));
    }

    private static FraudCase caseWith(
            FraudCaseStatus status, String assignee, long version, String resolvedBy,
            FraudCaseResolutionOutcome outcome, String rationale) {
        Instant now = Instant.parse("2026-08-01T10:00:00Z");
        return new FraudCase(
                UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(),
                status, assignee, version, now, "b".repeat(64), "merchant", "5411",
                new BigDecimal("10.00"), "EUR", "DE", TransactionChannel.ECOMMERCE,
                now, NonFraudResult.PASSED, AuthorizationDecision.APPROVED, null,
                FraudAssessment.REVIEW, 15, true, now, now, outcome, rationale,
                outcome == null ? null : now, resolvedBy, List.of());
    }
}
