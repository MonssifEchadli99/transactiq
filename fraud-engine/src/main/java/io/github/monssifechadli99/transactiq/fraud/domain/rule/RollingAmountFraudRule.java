package io.github.monssifechadli99.transactiq.fraud.domain.rule;

import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentRequest;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudRuleSeverity;
import io.github.monssifechadli99.transactiq.fraud.domain.MatchedFraudRule;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class RollingAmountFraudRule implements FraudRule {

    public static final String RULE_CODE = "ROLLING_AMOUNT";

    private final Duration window;
    private final Map<String, AmountThresholds> thresholdsByCurrency;

    public RollingAmountFraudRule(
            Duration window,
            Map<String, AmountThresholds> thresholdsByCurrency) {
        this.window = requirePositive(window, "rolling-amount window");
        Objects.requireNonNull(thresholdsByCurrency, "thresholdsByCurrency must not be null");
        Map<String, AmountThresholds> validatedThresholds = new LinkedHashMap<>();
        thresholdsByCurrency.forEach((currency, thresholds) -> {
            requireNonBlank(currency, "rolling-amount currency configuration key");
            validatedThresholds.put(
                    currency,
                    Objects.requireNonNull(thresholds, "rolling-amount thresholds must not be null"));
        });
        this.thresholdsByCurrency = Map.copyOf(validatedThresholds);
    }

    @Override
    public Optional<MatchedFraudRule> evaluate(FraudRuleContext context) {
        Objects.requireNonNull(context, "context must not be null");
        FraudAssessmentRequest request = context.request();
        AmountThresholds thresholds = thresholdsByCurrency.get(request.currency());
        if (thresholds == null) {
            return Optional.empty();
        }

        BigDecimal total = context.velocitySnapshot().rollingAmount(request.currency());
        if (total.compareTo(thresholds.highRisk()) >= 0) {
            return Optional.of(match(request.currency(), total, thresholds.highRisk(), FraudRuleSeverity.HIGH_RISK));
        }
        if (total.compareTo(thresholds.review()) >= 0) {
            return Optional.of(match(request.currency(), total, thresholds.review(), FraudRuleSeverity.REVIEW));
        }
        return Optional.empty();
    }

    private MatchedFraudRule match(
            String currency,
            BigDecimal total,
            BigDecimal threshold,
            FraudRuleSeverity severity) {
        String evidence = "rolling total " + currency + " " + total.toPlainString()
                + " in synthetic " + windowDescription(window) + " window met "
                + severity + " threshold " + threshold.toPlainString();
        return new MatchedFraudRule(RULE_CODE, severity, evidence);
    }

    private static Duration requirePositive(Duration value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private static String windowDescription(Duration value) {
        long milliseconds = value.toMillis();
        return milliseconds % 1000 == 0
                ? milliseconds / 1000 + "-second"
                : milliseconds + "-millisecond";
    }
}
