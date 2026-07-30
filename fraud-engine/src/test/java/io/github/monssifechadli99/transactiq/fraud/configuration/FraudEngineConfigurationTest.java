package io.github.monssifechadli99.transactiq.fraud.configuration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.fraud.application.port.out.VelocityAttemptRecorder;
import io.github.monssifechadli99.transactiq.fraud.domain.velocity.VelocitySnapshot;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FraudEngineConfigurationTest {

    private final ApplicationContextRunner contextRunner = baseContextRunner()
            .withPropertyValues(defaultScoringProperties());

    private static ApplicationContextRunner baseContextRunner() {
        return new ApplicationContextRunner()
            .withBean(VelocityAttemptRecorder.class, () -> (request, observedAt) -> new VelocitySnapshot(
                    observedAt,
                    1,
                    Map.of(request.currency(), request.amount()),
                    Set.of(request.country())))
            .withPropertyValues(
                    "fraud.rules.amount-thresholds.EUR.review=1000.00",
                    "fraud.rules.amount-thresholds.EUR.high-risk=2500.00",
                    "fraud.rules.transaction-count.window=60s",
                    "fraud.rules.transaction-count.review-threshold=5",
                    "fraud.rules.transaction-count.high-risk-threshold=10",
                    "fraud.rules.rolling-amount.window=5m",
                    "fraud.rules.rolling-amount.thresholds.EUR.review=3000.00",
                    "fraud.rules.rolling-amount.thresholds.EUR.high-risk=5000.00",
                    "fraud.rules.country-switch.window=10m",
                    "fraud.velocity.deduplication-retention=24h")
                .withUserConfiguration(FraudEngineConfiguration.class);
    }

    @Test
    void validConfigurationStartsAndConstructsAllRules() {
        contextRunner.withPropertyValues(
                        "fraud.rules.merchant-profiles.REVIEW[0]=merchant-review",
                        "fraud.rules.merchant-profiles.HIGH_RISK[0]=merchant-high-risk",
                        "fraud.rules.risky-mcc.REVIEW[0]=7995",
                        "fraud.rules.risky-mcc.HIGH_RISK[0]=6051")
                .run(context -> {
                    assertTrue(context.isRunning());
                    assertNotNull(context.getBean("amountThresholdFraudRule"));
                    assertNotNull(context.getBean("merchantProfileFraudRule"));
                    assertNotNull(context.getBean("riskyMerchantCategoryCodeFraudRule"));
                    assertNotNull(context.getBean("transactionCountFraudRule"));
                    assertNotNull(context.getBean("rollingAmountFraudRule"));
                    assertNotNull(context.getBean("countrySwitchFraudRule"));
                    assertNotNull(context.getBean("fraudScoringPolicy"));
                    assertNotNull(context.getBean("velocityTrackingSettings"));
                });
    }

    @Test
    void missingScoreMappingPreventsStartup() {
        assertStartupFailsWith(
                baseContextRunner().withPropertyValues(scoringPropertiesWithoutCountrySwitch()),
                "missing fraud score mappings");
    }

    @Test
    void unknownRuleCodePreventsStartup() {
        assertStartupFailsWith(
                "unsupported fraud score mapping",
                "fraud.scoring.contributions[0].rule-code=UNKNOWN_RULE");
    }

    @Test
    void unsupportedCountrySwitchReviewMappingPreventsStartup() {
        assertStartupFailsWith(
                "unsupported fraud score mapping COUNTRY_SWITCH/REVIEW",
                "fraud.scoring.contributions[0].rule-code=COUNTRY_SWITCH",
                "fraud.scoring.contributions[0].severity=REVIEW");
    }

    @Test
    void duplicateScoreMappingPreventsStartup() {
        assertStartupFailsWith(
                "duplicate fraud score mapping AMOUNT_THRESHOLD/REVIEW",
                "fraud.scoring.contributions[11].rule-code=AMOUNT_THRESHOLD",
                "fraud.scoring.contributions[11].severity=REVIEW",
                "fraud.scoring.contributions[11].points=15");
    }

    @Test
    void nonIntegerScoreContributionPreventsBinding() {
        assertStartupFailsWith(
                "Failed to bind properties",
                "fraud.scoring.contributions[0].points=15.5");
    }

    @Test
    void nonPositiveScoreContributionPreventsStartup() {
        assertStartupFailsWith(
                "fraud score contribution must be between 1 and 100",
                "fraud.scoring.contributions[0].points=0");
    }

    @Test
    void scoreContributionAboveOneHundredPreventsStartup() {
        assertStartupFailsWith(
                "fraud score contribution must be between 1 and 100",
                "fraud.scoring.contributions[0].points=101");
    }

    @Test
    void highRiskScoreContributionBelowSeventyPreventsStartup() {
        assertStartupFailsWith(
                "HIGH_RISK fraud score contribution must be at least 70",
                "fraud.scoring.contributions[1].points=69");
    }

    @Test
    void possibleReviewSumAboveSixtyNinePreventsStartup() {
        assertStartupFailsWith(
                "sum of all REVIEW fraud score contributions must not exceed 69",
                "fraud.scoring.contributions[0].points=20");
    }

    @Test
    void nonPositiveThresholdPreventsStartup() {
        assertStartupFailsWith(
                "review threshold must be positive",
                "fraud.rules.amount-thresholds.EUR.review=0.00",
                "fraud.rules.amount-thresholds.EUR.high-risk=2500.00");
    }

    @Test
    void highRiskThresholdNotGreaterThanReviewPreventsStartup() {
        assertStartupFailsWith(
                "highRisk threshold must be greater than review threshold",
                "fraud.rules.amount-thresholds.EUR.review=2500.00",
                "fraud.rules.amount-thresholds.EUR.high-risk=2500.00");
    }

    @Test
    void merchantConfiguredForBothSeveritiesPreventsStartup() {
        assertStartupFailsWith(
                "must not be configured for both severities",
                "fraud.rules.merchant-profiles.REVIEW[0]=merchant-shared",
                "fraud.rules.merchant-profiles.HIGH_RISK[0]=merchant-shared");
    }

    @Test
    void unsupportedSeverityPreventsStartup() {
        assertStartupFailsWith(
                "unsupported severity",
                "fraud.rules.risky-mcc.BLOCK[0]=7995");
    }

    @Test
    void blankRuleKeyPreventsStartup() {
        assertStartupFailsWith(
                "rule key must not be blank",
                "fraud.rules.merchant-profiles.REVIEW[0]=");
    }

    @Test
    void nonPositiveVelocityWindowPreventsStartup() {
        assertStartupFailsWith(
                "transaction-count window must be positive",
                "fraud.rules.transaction-count.window=0s");
    }

    @Test
    void nonPositiveTransactionCountThresholdPreventsStartup() {
        assertStartupFailsWith(
                "transaction-count review threshold must be positive",
                "fraud.rules.transaction-count.review-threshold=0");
    }

    @Test
    void nonWholeTransactionCountThresholdPreventsStartup() {
        assertStartupFailsWith(
                "Failed to bind properties",
                "fraud.rules.transaction-count.review-threshold=5.5");
    }

    @Test
    void transactionCountHighRiskThresholdMustExceedReviewThreshold() {
        assertStartupFailsWith(
                "transaction-count highRisk threshold must be greater than review threshold",
                "fraud.rules.transaction-count.review-threshold=5",
                "fraud.rules.transaction-count.high-risk-threshold=5");
    }

    @Test
    void nonPositiveRollingAmountThresholdPreventsStartup() {
        assertStartupFailsWith(
                "review threshold must be positive",
                "fraud.rules.rolling-amount.thresholds.EUR.review=0.00");
    }

    @Test
    void rollingAmountHighRiskThresholdMustExceedReviewThreshold() {
        assertStartupFailsWith(
                "highRisk threshold must be greater than review threshold",
                "fraud.rules.rolling-amount.thresholds.EUR.review=5000.00",
                "fraud.rules.rolling-amount.thresholds.EUR.high-risk=5000.00");
    }

    @Test
    void blankRollingAmountCurrencyPreventsStartup() {
        assertStartupFailsWith(
                "Could not bind properties",
                "fraud.rules.rolling-amount.thresholds.[].review=3000.00",
                "fraud.rules.rolling-amount.thresholds.[].high-risk=5000.00");
    }

    @Test
    void deduplicationRetentionMustExceedLongestWindow() {
        assertStartupFailsWith(
                "deduplication retention must be longer than every velocity window",
                "fraud.velocity.deduplication-retention=10m");
    }

    private void assertStartupFailsWith(String expectedMessage, String... properties) {
        assertStartupFailsWith(contextRunner.withPropertyValues(properties), expectedMessage);
    }

    private void assertStartupFailsWith(
            ApplicationContextRunner runner, String expectedMessage) {
        runner.run(context -> {
            Throwable startupFailure = context.getStartupFailure();
            assertNotNull(startupFailure);
            Throwable cause = startupFailure;
            boolean messageFound = false;
            while (cause != null) {
                messageFound |= cause.getMessage() != null && cause.getMessage().contains(expectedMessage);
                cause = cause.getCause();
            }
            assertTrue(
                    messageFound,
                    () -> "Expected startup failure chain to contain '" + expectedMessage + "'");
        });
    }

    private static String[] defaultScoringProperties() {
        return new String[] {
            "fraud.scoring.contributions[0].rule-code=AMOUNT_THRESHOLD",
            "fraud.scoring.contributions[0].severity=REVIEW",
            "fraud.scoring.contributions[0].points=15",
            "fraud.scoring.contributions[1].rule-code=AMOUNT_THRESHOLD",
            "fraud.scoring.contributions[1].severity=HIGH_RISK",
            "fraud.scoring.contributions[1].points=70",
            "fraud.scoring.contributions[2].rule-code=MERCHANT_PROFILE",
            "fraud.scoring.contributions[2].severity=REVIEW",
            "fraud.scoring.contributions[2].points=15",
            "fraud.scoring.contributions[3].rule-code=MERCHANT_PROFILE",
            "fraud.scoring.contributions[3].severity=HIGH_RISK",
            "fraud.scoring.contributions[3].points=75",
            "fraud.scoring.contributions[4].rule-code=RISKY_MCC",
            "fraud.scoring.contributions[4].severity=REVIEW",
            "fraud.scoring.contributions[4].points=10",
            "fraud.scoring.contributions[5].rule-code=RISKY_MCC",
            "fraud.scoring.contributions[5].severity=HIGH_RISK",
            "fraud.scoring.contributions[5].points=70",
            "fraud.scoring.contributions[6].rule-code=TRANSACTION_COUNT",
            "fraud.scoring.contributions[6].severity=REVIEW",
            "fraud.scoring.contributions[6].points=10",
            "fraud.scoring.contributions[7].rule-code=TRANSACTION_COUNT",
            "fraud.scoring.contributions[7].severity=HIGH_RISK",
            "fraud.scoring.contributions[7].points=70",
            "fraud.scoring.contributions[8].rule-code=ROLLING_AMOUNT",
            "fraud.scoring.contributions[8].severity=REVIEW",
            "fraud.scoring.contributions[8].points=15",
            "fraud.scoring.contributions[9].rule-code=ROLLING_AMOUNT",
            "fraud.scoring.contributions[9].severity=HIGH_RISK",
            "fraud.scoring.contributions[9].points=70",
            "fraud.scoring.contributions[10].rule-code=COUNTRY_SWITCH",
            "fraud.scoring.contributions[10].severity=HIGH_RISK",
            "fraud.scoring.contributions[10].points=80"
        };
    }

    private static String[] scoringPropertiesWithoutCountrySwitch() {
        String[] defaults = defaultScoringProperties();
        return java.util.Arrays.copyOf(defaults, defaults.length - 3);
    }
}
