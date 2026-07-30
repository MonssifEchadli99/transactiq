package io.github.monssifechadli99.transactiq.fraud.configuration;

import io.github.monssifechadli99.transactiq.fraud.application.port.in.FraudAssessmentUseCase;
import io.github.monssifechadli99.transactiq.fraud.application.port.out.VelocityAttemptRecorder;
import io.github.monssifechadli99.transactiq.fraud.application.service.RuleBasedFraudAssessmentService;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudRuleSeverity;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudScoringPolicy;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudScoringPolicy.ConfiguredContribution;
import io.github.monssifechadli99.transactiq.fraud.domain.rule.AmountThresholdFraudRule;
import io.github.monssifechadli99.transactiq.fraud.domain.rule.AmountThresholds;
import io.github.monssifechadli99.transactiq.fraud.domain.rule.CountrySwitchFraudRule;
import io.github.monssifechadli99.transactiq.fraud.domain.rule.FraudRule;
import io.github.monssifechadli99.transactiq.fraud.domain.rule.MerchantProfileFraudRule;
import io.github.monssifechadli99.transactiq.fraud.domain.rule.RollingAmountFraudRule;
import io.github.monssifechadli99.transactiq.fraud.domain.rule.RiskyMerchantCategoryCodeFraudRule;
import io.github.monssifechadli99.transactiq.fraud.domain.rule.TransactionCountFraudRule;
import io.github.monssifechadli99.transactiq.fraud.domain.velocity.VelocityTrackingSettings;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    FraudRulesProperties.class,
    FraudVelocityProperties.class,
    FraudScoringProperties.class
})
public class FraudEngineConfiguration {

    @Bean
    FraudRule amountThresholdFraudRule(FraudRulesProperties properties) {
        return new AmountThresholdFraudRule(
                amountThresholds(properties.amountThresholds(), "amount-threshold"));
    }

    @Bean
    FraudRule merchantProfileFraudRule(FraudRulesProperties properties) {
        return new MerchantProfileFraudRule(classificationsByKey(
                properties.merchantProfiles(), "merchant-profile"));
    }

    @Bean
    FraudRule riskyMerchantCategoryCodeFraudRule(FraudRulesProperties properties) {
        return new RiskyMerchantCategoryCodeFraudRule(classificationsByKey(
                properties.riskyMcc(), "risky-MCC"));
    }

    @Bean
    FraudRule transactionCountFraudRule(FraudRulesProperties properties) {
        FraudRulesProperties.TransactionCountProperties configuration =
                requireConfigured(properties.transactionCount(), "transaction-count rule");
        return new TransactionCountFraudRule(
                configuration.window(),
                requireConfigured(configuration.reviewThreshold(), "transaction-count review threshold"),
                requireConfigured(configuration.highRiskThreshold(), "transaction-count highRisk threshold"));
    }

    @Bean
    FraudRule rollingAmountFraudRule(FraudRulesProperties properties) {
        FraudRulesProperties.RollingAmountProperties configuration =
                requireConfigured(properties.rollingAmount(), "rolling-amount rule");
        return new RollingAmountFraudRule(
                configuration.window(),
                amountThresholds(configuration.thresholds(), "rolling-amount"));
    }

    @Bean
    FraudRule countrySwitchFraudRule(FraudRulesProperties properties) {
        FraudRulesProperties.CountrySwitchProperties configuration =
                requireConfigured(properties.countrySwitch(), "country-switch rule");
        return new CountrySwitchFraudRule(configuration.window());
    }

    @Bean
    VelocityTrackingSettings velocityTrackingSettings(
            FraudRulesProperties rules,
            FraudVelocityProperties velocity) {
        return new VelocityTrackingSettings(
                requireConfigured(rules.transactionCount(), "transaction-count rule").window(),
                requireConfigured(rules.rollingAmount(), "rolling-amount rule").window(),
                requireConfigured(rules.countrySwitch(), "country-switch rule").window(),
                velocity.deduplicationRetention());
    }

    @Bean
    Clock fraudObservationClock() {
        return Clock.systemUTC();
    }

    @Bean
    FraudScoringPolicy fraudScoringPolicy(FraudScoringProperties properties) {
        List<ConfiguredContribution> contributions = properties.contributions().stream()
                .map(configured -> new ConfiguredContribution(
                        requireNonBlank(configured.ruleCode(), "fraud score rule code"),
                        parseSeverity(configured.severity(), "fraud scoring"),
                        requireConfigured(configured.points(), "fraud score points")))
                .toList();
        return new FraudScoringPolicy(contributions);
    }

    @Bean
    FraudAssessmentUseCase fraudAssessmentUseCase(
            List<FraudRule> rules,
            VelocityAttemptRecorder velocityAttemptRecorder,
            Clock fraudObservationClock,
            FraudScoringPolicy fraudScoringPolicy) {
        return new RuleBasedFraudAssessmentService(
                rules,
                velocityAttemptRecorder,
                fraudObservationClock,
                fraudScoringPolicy);
    }

    private static Map<String, AmountThresholds> amountThresholds(
            Map<String, FraudRulesProperties.AmountThresholdProperties> configuredThresholds,
            String configurationName) {
        Map<String, AmountThresholds> thresholdsByCurrency = new LinkedHashMap<>();
        configuredThresholds.forEach((currency, thresholds) -> {
            requireNonBlank(currency, configurationName + " currency key");
            if (thresholds == null) {
                throw new IllegalArgumentException(
                        configurationName + " thresholds must not be null for " + currency);
            }
            thresholdsByCurrency.put(
                    currency,
                    new AmountThresholds(thresholds.review(), thresholds.highRisk()));
        });
        return Map.copyOf(thresholdsByCurrency);
    }

    private static Map<String, FraudRuleSeverity> classificationsByKey(
            Map<String, List<String>> keysBySeverity,
            String configurationName) {
        Map<String, FraudRuleSeverity> classifications = new LinkedHashMap<>();
        keysBySeverity.forEach((configuredSeverity, configuredKeys) -> {
            FraudRuleSeverity severity = parseSeverity(configuredSeverity, configurationName);
            if (configuredKeys == null) {
                throw new IllegalArgumentException(configurationName + " rule keys must not be null");
            }
            configuredKeys.forEach(configuredKey -> {
                requireNonBlank(configuredKey, configurationName + " rule key");
                FraudRuleSeverity previous = classifications.putIfAbsent(configuredKey, severity);
                if (previous != null && previous != severity) {
                    throw new IllegalArgumentException(
                            configurationName + " rule key " + configuredKey
                                    + " must not be configured for both severities");
                }
            });
        });
        return Map.copyOf(classifications);
    }

    private static FraudRuleSeverity parseSeverity(String value, String configurationName) {
        requireNonBlank(value, configurationName + " severity");
        try {
            return FraudRuleSeverity.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    configurationName + " has unsupported severity " + value,
                    exception);
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static <T> T requireConfigured(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must be configured");
        }
        return value;
    }
}
