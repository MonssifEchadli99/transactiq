package io.github.monssifechadli99.transactiq.fraud.domain.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentRequest;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudChannel;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudRuleSeverity;
import io.github.monssifechadli99.transactiq.fraud.domain.MatchedFraudRule;
import io.github.monssifechadli99.transactiq.fraud.domain.velocity.VelocitySnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AmountThresholdFraudRuleTest {

    private final AmountThresholdFraudRule rule = new AmountThresholdFraudRule(Map.of(
            "EUR", new AmountThresholds(new BigDecimal("1000.00"), new BigDecimal("2500.00"))));

    @Test
    void amountBelowReviewThresholdDoesNotMatch() {
        assertTrue(rule.evaluate(context(request("999.99", "EUR"))).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
        "1000.00, REVIEW, 1000.00",
        "2499.99, REVIEW, 1000.00",
        "2500.00, HIGH_RISK, 2500.00"
    })
    void configuredBoundariesProduceOneMatch(
            String amount,
            FraudRuleSeverity expectedSeverity,
            String expectedThreshold) {
        Optional<MatchedFraudRule> result = rule.evaluate(context(request(amount, "EUR")));

        assertTrue(result.isPresent());
        MatchedFraudRule match = result.orElseThrow();
        assertEquals(AmountThresholdFraudRule.RULE_CODE, match.ruleCode());
        assertEquals(expectedSeverity, match.severity());
        assertEquals(
                "amount EUR " + amount + " met synthetic " + expectedSeverity
                        + " threshold " + expectedThreshold,
                match.evidence());
    }

    @Test
    void unconfiguredCurrencyDoesNotMatchAndIsNotConverted() {
        assertTrue(rule.evaluate(context(request("3000.00", "USD"))).isEmpty());
    }

    private static FraudRuleContext context(FraudAssessmentRequest request) {
        return new FraudRuleContext(
                request,
                new VelocitySnapshot(request.transactionTime(), 0, Map.of(), Set.of()));
    }

    private static FraudAssessmentRequest request(String amount, String currency) {
        return new FraudAssessmentRequest(
                UUID.fromString("f2b1c9d0-6e3a-4c1b-9b7a-2b6a1e9c7d44"),
                "tok_A1B2C3D4",
                "merchant-123",
                "5732",
                new BigDecimal(amount),
                currency,
                "DE",
                FraudChannel.ECOMMERCE,
                Instant.parse("2026-07-19T10:15:30Z"));
    }
}
