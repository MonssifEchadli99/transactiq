package io.github.monssifechadli99.transactiq.fraud.domain.rule;

import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentRequest;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudRuleSeverity;
import io.github.monssifechadli99.transactiq.fraud.domain.MatchedFraudRule;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class RiskyMerchantCategoryCodeFraudRule implements FraudRule {

    public static final String RULE_CODE = "RISKY_MCC";

    private final Map<String, FraudRuleSeverity> severitiesByMerchantCategoryCode;

    public RiskyMerchantCategoryCodeFraudRule(
            Map<String, FraudRuleSeverity> severitiesByMerchantCategoryCode) {
        Objects.requireNonNull(
                severitiesByMerchantCategoryCode,
                "severitiesByMerchantCategoryCode must not be null");
        Map<String, FraudRuleSeverity> validatedSeverities = new LinkedHashMap<>();
        severitiesByMerchantCategoryCode.forEach((merchantCategoryCode, severity) -> {
            requireNonBlank(merchantCategoryCode, "MCC configuration key");
            validatedSeverities.put(
                    merchantCategoryCode,
                    Objects.requireNonNull(severity, "MCC severity must not be null"));
        });
        this.severitiesByMerchantCategoryCode = Map.copyOf(validatedSeverities);
    }

    @Override
    public Optional<MatchedFraudRule> evaluate(FraudRuleContext context) {
        Objects.requireNonNull(context, "context must not be null");
        FraudAssessmentRequest request = context.request();
        FraudRuleSeverity severity = severitiesByMerchantCategoryCode.get(request.merchantCategoryCode());
        if (severity == null) {
            return Optional.empty();
        }

        String evidence = "MCC " + request.merchantCategoryCode()
                + " has synthetic " + severity + " classification";
        return Optional.of(new MatchedFraudRule(RULE_CODE, severity, evidence));
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
