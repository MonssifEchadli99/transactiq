package io.github.monssifechadli99.transactiq.case_management.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FraudCaseClaimPolicyTest {
    private final FraudCaseClaimPolicy policy = new FraudCaseClaimPolicy();

    @Test
    void classifiesApprovedClaimStatesInBusinessPriorityOrder() {
        assertEquals(FraudCaseClaimPolicy.Decision.CLAIMABLE,
                policy.classify(caseWith(FraudCaseStatus.NEW, null, 0), "analyst-a", 0));
        assertEquals(FraudCaseClaimPolicy.Decision.VERSION_CONFLICT,
                policy.classify(caseWith(FraudCaseStatus.NEW, null, 1), "analyst-a", 0));
        assertEquals(FraudCaseClaimPolicy.Decision.ALREADY_ASSIGNED,
                policy.classify(caseWith(FraudCaseStatus.NEW, "analyst-b", 1), "analyst-a", 0));
        assertEquals(FraudCaseClaimPolicy.Decision.ALREADY_CLAIMED_BY_ANALYST,
                policy.classify(caseWith(FraudCaseStatus.IN_REVIEW, "Analyst-A", 1), "Analyst-A", 0));
        assertEquals(FraudCaseClaimPolicy.Decision.ALREADY_ASSIGNED,
                policy.classify(caseWith(FraudCaseStatus.IN_REVIEW, "Analyst-A", 1), "analyst-a", 0));
        assertEquals(FraudCaseClaimPolicy.Decision.NOT_CLAIMABLE,
                policy.classify(caseWith(FraudCaseStatus.RESOLVED, "analyst-b", 2), "analyst-a", 2));
    }

    private static FraudCase caseWith(FraudCaseStatus status, String assignee, long version) {
        Instant now = Instant.parse("2026-08-01T10:00:00Z");
        return new FraudCase(
                UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(),
                status, assignee, version, now, "b".repeat(64), "merchant", "5411",
                new BigDecimal("10.00"), "EUR", "DE", TransactionChannel.ECOMMERCE,
                now, NonFraudResult.PASSED, AuthorizationDecision.APPROVED, null,
                FraudAssessment.REVIEW, 15, true, now, now, List.of());
    }
}
