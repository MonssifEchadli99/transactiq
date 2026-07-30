package io.github.monssifechadli99.transactiq.fraud.domain.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentRequest;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudChannel;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudRuleSeverity;
import io.github.monssifechadli99.transactiq.fraud.domain.MatchedFraudRule;
import io.github.monssifechadli99.transactiq.fraud.domain.velocity.VelocitySnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RollingAmountFraudRuleTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-07-19T10:16:00Z");

    private final RollingAmountFraudRule rule = new RollingAmountFraudRule(
            Duration.ofMinutes(5),
            Map.of("EUR", new AmountThresholds(
                    new BigDecimal("3000.00"),
                    new BigDecimal("5000.00"))));

    @Test
    void totalBelowReviewThresholdDoesNotMatch() {
        assertTrue(rule.evaluate(context("EUR", "2999.99")).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
        "3000.00, REVIEW, 3000.00",
        "4999.99, REVIEW, 3000.00",
        "5000.00, HIGH_RISK, 5000.00"
    })
    void exactRollingAmountBoundariesProduceOneMatch(
            String total,
            FraudRuleSeverity expectedSeverity,
            String expectedThreshold) {
        Optional<MatchedFraudRule> result = rule.evaluate(context("EUR", total));

        assertTrue(result.isPresent());
        MatchedFraudRule match = result.orElseThrow();
        assertEquals(RollingAmountFraudRule.RULE_CODE, match.ruleCode());
        assertEquals(expectedSeverity, match.severity());
        assertEquals(
                "rolling total EUR " + total
                        + " in synthetic 300-second window met " + expectedSeverity
                        + " threshold " + expectedThreshold,
                match.evidence());
    }

    @Test
    void unconfiguredCurrencyDoesNotMatchOrConvert() {
        assertTrue(rule.evaluate(context("USD", "6000.00")).isEmpty());
    }

    private static FraudRuleContext context(String currency, String rollingTotal) {
        FraudAssessmentRequest request = request(currency);
        return new FraudRuleContext(
                request,
                new VelocitySnapshot(
                        OBSERVED_AT,
                        1,
                        Map.of(currency, new BigDecimal(rollingTotal)),
                        Set.of("DE")));
    }

    private static FraudAssessmentRequest request(String currency) {
        return new FraudAssessmentRequest(
                UUID.fromString("f2b1c9d0-6e3a-4c1b-9b7a-2b6a1e9c7d44"),
                "tok_A1B2C3D4",
                "merchant-123",
                "5732",
                new BigDecimal("10.00"),
                currency,
                "DE",
                FraudChannel.ECOMMERCE,
                Instant.parse("2026-07-19T10:15:30Z"));
    }
}
