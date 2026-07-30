package io.github.monssifechadli99.transactiq.fraud.configuration;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fraud.rules")
public record FraudRulesProperties(
        Map<String, AmountThresholdProperties> amountThresholds,
        Map<String, List<String>> merchantProfiles,
        Map<String, List<String>> riskyMcc,
        TransactionCountProperties transactionCount,
        RollingAmountProperties rollingAmount,
        CountrySwitchProperties countrySwitch) {

    public FraudRulesProperties {
        amountThresholds = amountThresholds == null ? Map.of() : Map.copyOf(amountThresholds);
        merchantProfiles = merchantProfiles == null ? Map.of() : Map.copyOf(merchantProfiles);
        riskyMcc = riskyMcc == null ? Map.of() : Map.copyOf(riskyMcc);
    }

    public record AmountThresholdProperties(BigDecimal review, BigDecimal highRisk) {}

    public record TransactionCountProperties(
            Duration window,
            Long reviewThreshold,
            Long highRiskThreshold) {}

    public record RollingAmountProperties(
            Duration window,
            Map<String, AmountThresholdProperties> thresholds) {

        public RollingAmountProperties {
            thresholds = thresholds == null ? Map.of() : Map.copyOf(thresholds);
        }
    }

    public record CountrySwitchProperties(Duration window) {}
}
