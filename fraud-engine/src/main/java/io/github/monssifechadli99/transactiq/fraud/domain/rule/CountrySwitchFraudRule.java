package io.github.monssifechadli99.transactiq.fraud.domain.rule;

import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentRequest;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudRuleSeverity;
import io.github.monssifechadli99.transactiq.fraud.domain.MatchedFraudRule;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class CountrySwitchFraudRule implements FraudRule {

    public static final String RULE_CODE = "COUNTRY_SWITCH";

    private final Duration window;

    public CountrySwitchFraudRule(Duration window) {
        this.window = requirePositive(window, "country-switch window");
    }

    @Override
    public Optional<MatchedFraudRule> evaluate(FraudRuleContext context) {
        Objects.requireNonNull(context, "context must not be null");
        FraudAssessmentRequest request = context.request();
        Optional<String> differentCountry = context.velocitySnapshot().observedCountries().stream()
                .filter(country -> !country.equals(request.country()))
                .sorted()
                .findFirst();
        if (differentCountry.isEmpty()) {
            return Optional.empty();
        }

        String evidence = "current country " + request.country() + " differs from observed country "
                + differentCountry.orElseThrow() + " within synthetic "
                + windowDescription(window) + " window";
        return Optional.of(new MatchedFraudRule(
                RULE_CODE,
                FraudRuleSeverity.HIGH_RISK,
                evidence));
    }

    private static Duration requirePositive(Duration value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static String windowDescription(Duration value) {
        long milliseconds = value.toMillis();
        return milliseconds % 1000 == 0
                ? milliseconds / 1000 + "-second"
                : milliseconds + "-millisecond";
    }
}
