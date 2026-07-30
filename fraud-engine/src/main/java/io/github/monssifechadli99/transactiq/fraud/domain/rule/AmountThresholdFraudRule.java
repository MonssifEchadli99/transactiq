package io.github.monssifechadli99.transactiq.fraud.domain.rule;

import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentRequest;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudRuleSeverity;
import io.github.monssifechadli99.transactiq.fraud.domain.MatchedFraudRule;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class AmountThresholdFraudRule implements FraudRule {

    public static final String RULE_CODE = "AMOUNT_THRESHOLD";

    private final Map<String, AmountThresholds> thresholdsByCurrency;

    public AmountThresholdFraudRule(Map<String, AmountThresholds> thresholdsByCurrency) {
        Objects.requireNonNull(thresholdsByCurrency, "thresholdsByCurrency must not be null");
        Map<String, AmountThresholds> validatedThresholds = new LinkedHashMap<>();
        thresholdsByCurrency.forEach((currency, thresholds) -> {
            requireNonBlank(currency, "currency configuration key");
            validatedThresholds.put(
                    currency,
                    Objects.requireNonNull(thresholds, "amount thresholds must not be null"));
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

        if (request.amount().compareTo(thresholds.highRisk()) >= 0) {
            return Optional.of(match(request, thresholds.highRisk(), FraudRuleSeverity.HIGH_RISK));
        }
        if (request.amount().compareTo(thresholds.review()) >= 0) {
            return Optional.of(match(request, thresholds.review(), FraudRuleSeverity.REVIEW));
        }
        return Optional.empty();
    }

    private static MatchedFraudRule match(
            FraudAssessmentRequest request,
            BigDecimal threshold,
            FraudRuleSeverity severity) {
        String evidence = "amount " + request.currency() + " " + request.amount().toPlainString()
                + " met synthetic " + severity + " threshold " + threshold.toPlainString();
        return new MatchedFraudRule(RULE_CODE, severity, evidence);
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
