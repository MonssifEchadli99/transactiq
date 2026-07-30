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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TransactionCountFraudRuleTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-07-19T10:16:00Z");

    private final TransactionCountFraudRule rule =
            new TransactionCountFraudRule(Duration.ofSeconds(60), 5, 10);

    @ParameterizedTest
    @CsvSource({"1", "4"})
    void firstFourAttemptsDoNotMatch(long count) {
        assertTrue(rule.evaluate(context(count)).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
        "5, REVIEW, 5",
        "9, REVIEW, 5",
        "10, HIGH_RISK, 10"
    })
    void exactCountBoundariesProduceOneMatch(
            long count,
            FraudRuleSeverity expectedSeverity,
            long expectedThreshold) {
        Optional<MatchedFraudRule> result = rule.evaluate(context(count));

        assertTrue(result.isPresent());
        MatchedFraudRule match = result.orElseThrow();
        assertEquals(TransactionCountFraudRule.RULE_CODE, match.ruleCode());
        assertEquals(expectedSeverity, match.severity());
        assertEquals(
                count + " attempts in synthetic 60-second window met "
                        + expectedSeverity + " threshold " + expectedThreshold,
                match.evidence());
    }

    private static FraudRuleContext context(long count) {
        FraudAssessmentRequest request = request();
        return new FraudRuleContext(
                request,
                new VelocitySnapshot(
                        OBSERVED_AT,
                        count,
                        Map.of("EUR", request.amount()),
                        Set.of("DE")));
    }

    private static FraudAssessmentRequest request() {
        return new FraudAssessmentRequest(
                UUID.fromString("f2b1c9d0-6e3a-4c1b-9b7a-2b6a1e9c7d44"),
                "tok_A1B2C3D4",
                "merchant-123",
                "5732",
                new BigDecimal("10.00"),
                "EUR",
                "DE",
                FraudChannel.ECOMMERCE,
                Instant.parse("2026-07-19T10:15:30Z"));
    }
}
