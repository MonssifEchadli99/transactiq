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

class CountrySwitchFraudRuleTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-07-19T10:16:00Z");

    private final CountrySwitchFraudRule rule = new CountrySwitchFraudRule(Duration.ofMinutes(10));

    @Test
    void firstObservedCountryDoesNotMatch() {
        assertTrue(rule.evaluate(context("DE", Set.of("DE"))).isEmpty());
    }

    @Test
    void repeatedSameCountryDoesNotMatch() {
        assertTrue(rule.evaluate(context("DE", Set.of("DE"))).isEmpty());
    }

    @Test
    void differentCountryInsideWindowProducesHighRisk() {
        Optional<MatchedFraudRule> result = rule.evaluate(context("FR", Set.of("DE", "FR")));

        assertTrue(result.isPresent());
        MatchedFraudRule match = result.orElseThrow();
        assertEquals(CountrySwitchFraudRule.RULE_CODE, match.ruleCode());
        assertEquals(FraudRuleSeverity.HIGH_RISK, match.severity());
        assertEquals(
                "current country FR differs from observed country DE within synthetic 600-second window",
                match.evidence());
    }

    private static FraudRuleContext context(String currentCountry, Set<String> observedCountries) {
        FraudAssessmentRequest request = request(currentCountry);
        return new FraudRuleContext(
                request,
                new VelocitySnapshot(
                        OBSERVED_AT,
                        1,
                        Map.of("EUR", request.amount()),
                        observedCountries));
    }

    private static FraudAssessmentRequest request(String country) {
        return new FraudAssessmentRequest(
                UUID.fromString("f2b1c9d0-6e3a-4c1b-9b7a-2b6a1e9c7d44"),
                "tok_A1B2C3D4",
                "merchant-123",
                "5732",
                new BigDecimal("10.00"),
                "EUR",
                country,
                FraudChannel.ECOMMERCE,
                Instant.parse("2026-07-19T10:15:30Z"));
    }
}
