package io.github.monssifechadli99.transactiq.fraud.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.monssifechadli99.transactiq.fraud.domain.FraudScoringPolicy.ConfiguredContribution;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FraudScoringPolicyTest {

    @ParameterizedTest
    @CsvSource({
        "AMOUNT_THRESHOLD, REVIEW, 15",
        "AMOUNT_THRESHOLD, HIGH_RISK, 70",
        "MERCHANT_PROFILE, REVIEW, 15",
        "MERCHANT_PROFILE, HIGH_RISK, 75",
        "RISKY_MCC, REVIEW, 10",
        "RISKY_MCC, HIGH_RISK, 70",
        "TRANSACTION_COUNT, REVIEW, 10",
        "TRANSACTION_COUNT, HIGH_RISK, 70",
        "ROLLING_AMOUNT, REVIEW, 15",
        "ROLLING_AMOUNT, HIGH_RISK, 70",
        "COUNTRY_SWITCH, HIGH_RISK, 80"
    })
    void eachDefaultSingleMatchHasItsConfiguredContribution(
            String ruleCode, FraudRuleSeverity severity, int expectedScore) {
        FraudRiskScore score = defaultPolicy().score(List.of(match(ruleCode, severity)));

        assertEquals(expectedScore, score.value());
        assertEquals(expectedScore, score.matchedRules().getFirst().scoreContribution());
    }

    @Test
    void multipleReviewMatchesAreSummed() {
        FraudRiskScore score = defaultPolicy().score(List.of(
                match("MERCHANT_PROFILE", FraudRuleSeverity.REVIEW),
                match("RISKY_MCC", FraudRuleSeverity.REVIEW)));

        assertEquals(25, score.value());
    }

    @Test
    void allFiveReviewContributionsTotalSixtyFive() {
        FraudRiskScore score = defaultPolicy().score(List.of(
                match("AMOUNT_THRESHOLD", FraudRuleSeverity.REVIEW),
                match("MERCHANT_PROFILE", FraudRuleSeverity.REVIEW),
                match("RISKY_MCC", FraudRuleSeverity.REVIEW),
                match("ROLLING_AMOUNT", FraudRuleSeverity.REVIEW),
                match("TRANSACTION_COUNT", FraudRuleSeverity.REVIEW)));

        assertEquals(65, score.value());
    }

    @Test
    void sumIsCappedAtOneHundredAndEveryContributionIsPreserved() {
        FraudRiskScore score = defaultPolicy().score(List.of(
                match("AMOUNT_THRESHOLD", FraudRuleSeverity.HIGH_RISK),
                match("COUNTRY_SWITCH", FraudRuleSeverity.HIGH_RISK),
                match("MERCHANT_PROFILE", FraudRuleSeverity.REVIEW)));

        assertEquals(100, score.value());
        assertEquals(List.of(70, 80, 15), score.matchedRules().stream()
                .map(ScoredFraudRuleMatch::scoreContribution)
                .toList());
    }

    static FraudScoringPolicy defaultPolicy() {
        return new FraudScoringPolicy(List.of(
                contribution("AMOUNT_THRESHOLD", FraudRuleSeverity.REVIEW, 15),
                contribution("AMOUNT_THRESHOLD", FraudRuleSeverity.HIGH_RISK, 70),
                contribution("MERCHANT_PROFILE", FraudRuleSeverity.REVIEW, 15),
                contribution("MERCHANT_PROFILE", FraudRuleSeverity.HIGH_RISK, 75),
                contribution("RISKY_MCC", FraudRuleSeverity.REVIEW, 10),
                contribution("RISKY_MCC", FraudRuleSeverity.HIGH_RISK, 70),
                contribution("TRANSACTION_COUNT", FraudRuleSeverity.REVIEW, 10),
                contribution("TRANSACTION_COUNT", FraudRuleSeverity.HIGH_RISK, 70),
                contribution("ROLLING_AMOUNT", FraudRuleSeverity.REVIEW, 15),
                contribution("ROLLING_AMOUNT", FraudRuleSeverity.HIGH_RISK, 70),
                contribution("COUNTRY_SWITCH", FraudRuleSeverity.HIGH_RISK, 80)));
    }

    private static ConfiguredContribution contribution(
            String ruleCode, FraudRuleSeverity severity, int points) {
        return new ConfiguredContribution(ruleCode, severity, points);
    }

    private static MatchedFraudRule match(String ruleCode, FraudRuleSeverity severity) {
        return new MatchedFraudRule(ruleCode, severity, "synthetic evidence");
    }
}
