package io.github.monssifechadli99.transactiq.fraud.domain.rule;

import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentRequest;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudRuleSeverity;
import io.github.monssifechadli99.transactiq.fraud.domain.MatchedFraudRule;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class MerchantProfileFraudRule implements FraudRule {

    public static final String RULE_CODE = "MERCHANT_PROFILE";

    private final Map<String, FraudRuleSeverity> profilesByMerchantId;

    public MerchantProfileFraudRule(Map<String, FraudRuleSeverity> profilesByMerchantId) {
        Objects.requireNonNull(profilesByMerchantId, "profilesByMerchantId must not be null");
        Map<String, FraudRuleSeverity> validatedProfiles = new LinkedHashMap<>();
        profilesByMerchantId.forEach((merchantId, severity) -> {
            requireNonBlank(merchantId, "merchant configuration key");
            validatedProfiles.put(
                    merchantId,
                    Objects.requireNonNull(severity, "merchant severity must not be null"));
        });
        this.profilesByMerchantId = Map.copyOf(validatedProfiles);
    }

    @Override
    public Optional<MatchedFraudRule> evaluate(FraudRuleContext context) {
        Objects.requireNonNull(context, "context must not be null");
        FraudAssessmentRequest request = context.request();
        FraudRuleSeverity severity = profilesByMerchantId.get(request.merchantId());
        if (severity == null) {
            return Optional.empty();
        }

        String evidence = "merchant " + request.merchantId() + " has synthetic " + severity + " profile";
        return Optional.of(new MatchedFraudRule(RULE_CODE, severity, evidence));
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
