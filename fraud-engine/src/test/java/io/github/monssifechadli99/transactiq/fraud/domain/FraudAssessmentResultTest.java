package io.github.monssifechadli99.transactiq.fraud.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FraudAssessmentResultTest {

    private static final ScoredFraudRuleMatch REVIEW_RULE = new ScoredFraudRuleMatch(
            "MERCHANT_PROFILE", FraudRuleSeverity.REVIEW,
            "merchant merchant-review has synthetic REVIEW profile", 15);

    private static final ScoredFraudRuleMatch HIGH_RISK_RULE = new ScoredFraudRuleMatch(
            "AMOUNT_THRESHOLD", FraudRuleSeverity.HIGH_RISK,
            "amount EUR 2600.00 met synthetic HIGH_RISK threshold 2500.00", 70);

    @Test
    void clearAssessmentRequiresZeroScoreAndNoMatchedRules() {
        FraudAssessmentResult result = new FraudAssessmentResult(
                FraudAssessment.CLEAR, 0, List.of());

        assertEquals(FraudAssessment.CLEAR, result.assessment());
        assertEquals(0, result.riskScore());
        assertTrue(result.matchedRules().isEmpty());
    }

    @Test
    void assessmentMustEqualHighestMatchedSeverity() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FraudAssessmentResult(
                        FraudAssessment.CLEAR, 15, List.of(REVIEW_RULE)));

        assertTrue(exception.getMessage().contains("REVIEW"));
    }

    @Test
    void riskScoreMustEqualCappedContributionSum() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FraudAssessmentResult(
                        FraudAssessment.REVIEW, 14, List.of(REVIEW_RULE)));
    }

    @Test
    void reviewScoreMustStayBelowHighRiskBoundary() {
        ScoredFraudRuleMatch oversizedReview = new ScoredFraudRuleMatch(
                "MERCHANT_PROFILE", FraudRuleSeverity.REVIEW, "synthetic evidence", 70);

        assertThrows(
                IllegalArgumentException.class,
                () -> new FraudAssessmentResult(
                        FraudAssessment.REVIEW, 70, List.of(oversizedReview)));
    }

    @Test
    void highRiskScoreMustReachHighRiskBoundary() {
        ScoredFraudRuleMatch undersizedHighRisk = new ScoredFraudRuleMatch(
                "AMOUNT_THRESHOLD", FraudRuleSeverity.HIGH_RISK, "synthetic evidence", 69);

        assertThrows(
                IllegalArgumentException.class,
                () -> new FraudAssessmentResult(
                        FraudAssessment.HIGH_RISK, 69, List.of(undersizedHighRisk)));
    }

    @Test
    void mixedSeveritiesPreserveHighRiskAssessmentAndCappedScore() {
        FraudAssessmentResult result = new FraudAssessmentResult(
                FraudAssessment.HIGH_RISK,
                85,
                List.of(HIGH_RISK_RULE, REVIEW_RULE));

        assertEquals(FraudAssessment.HIGH_RISK, result.assessment());
        assertEquals(85, result.riskScore());
    }

    @Test
    void matchedRulesMustBeAlphabeticalAndUnique() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FraudAssessmentResult(
                        FraudAssessment.HIGH_RISK,
                        85,
                        List.of(REVIEW_RULE, HIGH_RISK_RULE)));
    }

    @Test
    void matchedRulesAreImmutableAndDefensivelyCopied() {
        List<ScoredFraudRuleMatch> mutableRules = new ArrayList<>(List.of(REVIEW_RULE));
        FraudAssessmentResult result = new FraudAssessmentResult(
                FraudAssessment.REVIEW, 15, mutableRules);

        mutableRules.clear();

        assertEquals(List.of(REVIEW_RULE), result.matchedRules());
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.matchedRules().add(HIGH_RISK_RULE));
    }

    @Test
    void rejectsNullFields() {
        assertThrows(
                NullPointerException.class,
                () -> new FraudAssessmentResult(null, 0, List.of()));
        assertThrows(
                NullPointerException.class,
                () -> new FraudAssessmentResult(FraudAssessment.CLEAR, 0, null));
    }
}
