package io.github.monssifechadli99.transactiq.authorization.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AuthorizationPolicyTest {

    private final AuthorizationPolicy policy = new AuthorizationPolicy();

    @Test
    void approvesWhenFraudAssessmentIsClearAndNonFraudChecksPass() {
        AuthorizationOutcome outcome = policy.decide(
                FraudAssessment.CLEAR,
                NonFraudCheckResult.PASSED);

        assertInstanceOf(AuthorizationOutcome.Approved.class, outcome);
        assertEquals(AuthorizationDecision.APPROVED, outcome.decision());
        assertFalse(outcome.fraudCaseRequired());
    }

    @Test
    void declinesForInsufficientFundsWhenFraudAssessmentIsClear() {
        AuthorizationOutcome outcome = policy.decide(
                FraudAssessment.CLEAR,
                NonFraudCheckResult.INSUFFICIENT_FUNDS);

        AuthorizationOutcome.Declined declined =
                assertInstanceOf(AuthorizationOutcome.Declined.class, outcome);
        assertEquals(AuthorizationDecision.DECLINED, declined.decision());
        assertEquals(DeclineReason.INSUFFICIENT_FUNDS, declined.declineReason());
        assertFalse(declined.fraudCaseRequired());
    }

    @Test
    void declinesForFraudReviewWhenNonFraudChecksPass() {
        AuthorizationOutcome outcome = policy.decide(
                FraudAssessment.REVIEW,
                NonFraudCheckResult.PASSED);

        AuthorizationOutcome.Declined declined =
                assertInstanceOf(AuthorizationOutcome.Declined.class, outcome);
        assertEquals(AuthorizationDecision.DECLINED, declined.decision());
        assertEquals(DeclineReason.FRAUD_REVIEW_REQUIRED, declined.declineReason());
        assertTrue(declined.fraudCaseRequired());
    }

    @Test
    void declinesForHighFraudRiskWhenNonFraudChecksPass() {
        AuthorizationOutcome outcome = policy.decide(
                FraudAssessment.HIGH_RISK,
                NonFraudCheckResult.PASSED);

        AuthorizationOutcome.Declined declined =
                assertInstanceOf(AuthorizationOutcome.Declined.class, outcome);
        assertEquals(AuthorizationDecision.DECLINED, declined.decision());
        assertEquals(DeclineReason.HIGH_FRAUD_RISK, declined.declineReason());
        assertTrue(declined.fraudCaseRequired());
    }

    @Test
    void keepsInsufficientFundsAsPrimaryReasonAndRequiresCaseForFraudReview() {
        AuthorizationOutcome outcome = policy.decide(
                FraudAssessment.REVIEW,
                NonFraudCheckResult.INSUFFICIENT_FUNDS);

        AuthorizationOutcome.Declined declined =
                assertInstanceOf(AuthorizationOutcome.Declined.class, outcome);
        assertEquals(AuthorizationDecision.DECLINED, declined.decision());
        assertEquals(DeclineReason.INSUFFICIENT_FUNDS, declined.declineReason());
        assertTrue(declined.fraudCaseRequired());
    }

    @Test
    void keepsInsufficientFundsAsPrimaryReasonAndRequiresCaseForHighFraudRisk() {
        AuthorizationOutcome outcome = policy.decide(
                FraudAssessment.HIGH_RISK,
                NonFraudCheckResult.INSUFFICIENT_FUNDS);

        AuthorizationOutcome.Declined declined =
                assertInstanceOf(AuthorizationOutcome.Declined.class, outcome);
        assertEquals(AuthorizationDecision.DECLINED, declined.decision());
        assertEquals(DeclineReason.INSUFFICIENT_FUNDS, declined.declineReason());
        assertTrue(declined.fraudCaseRequired());
    }

    @Test
    void rejectsNullFraudAssessment() {
        assertThrows(
                NullPointerException.class,
                () -> policy.decide(null, NonFraudCheckResult.PASSED));
    }

    @Test
    void rejectsNullNonFraudCheckResult() {
        assertThrows(
                NullPointerException.class,
                () -> policy.decide(FraudAssessment.CLEAR, null));
    }

    @Test
    void rejectsNullDeclineReason() {
        assertThrows(
                NullPointerException.class,
                () -> new AuthorizationOutcome.Declined(null, false));
    }
}
