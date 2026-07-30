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

class RiskyMerchantCategoryCodeFraudRuleTest {

    private final RiskyMerchantCategoryCodeFraudRule rule = new RiskyMerchantCategoryCodeFraudRule(Map.of(
            "7995", FraudRuleSeverity.REVIEW,
            "6051", FraudRuleSeverity.HIGH_RISK));

    @ParameterizedTest
    @CsvSource({
        "7995, REVIEW",
        "6051, HIGH_RISK"
    })
    void configuredMccProducesItsConfiguredSeverity(
            String merchantCategoryCode,
            FraudRuleSeverity expectedSeverity) {
        Optional<MatchedFraudRule> result = rule.evaluate(context(request(merchantCategoryCode)));

        assertTrue(result.isPresent());
        MatchedFraudRule match = result.orElseThrow();
        assertEquals(RiskyMerchantCategoryCodeFraudRule.RULE_CODE, match.ruleCode());
        assertEquals(expectedSeverity, match.severity());
        assertEquals(
                "MCC " + merchantCategoryCode + " has synthetic " + expectedSeverity + " classification",
                match.evidence());
    }

    @Test
    void unconfiguredMccDoesNotMatch() {
        assertTrue(rule.evaluate(context(request("5732"))).isEmpty());
    }

    private static FraudRuleContext context(FraudAssessmentRequest request) {
        return new FraudRuleContext(
                request,
                new VelocitySnapshot(request.transactionTime(), 0, Map.of(), Set.of()));
    }

    private static FraudAssessmentRequest request(String merchantCategoryCode) {
        return new FraudAssessmentRequest(
                UUID.fromString("f2b1c9d0-6e3a-4c1b-9b7a-2b6a1e9c7d44"),
                "tok_A1B2C3D4",
                "merchant-123",
                merchantCategoryCode,
                new BigDecimal("10.00"),
                "EUR",
                "DE",
                FraudChannel.ECOMMERCE,
                Instant.parse("2026-07-19T10:15:30Z"));
    }
}
