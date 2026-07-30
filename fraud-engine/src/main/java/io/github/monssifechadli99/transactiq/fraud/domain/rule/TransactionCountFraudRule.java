package io.github.monssifechadli99.transactiq.fraud.domain.rule;

import io.github.monssifechadli99.transactiq.fraud.domain.FraudRuleSeverity;
import io.github.monssifechadli99.transactiq.fraud.domain.MatchedFraudRule;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class TransactionCountFraudRule implements FraudRule {

    public static final String RULE_CODE = "TRANSACTION_COUNT";

    private final Duration window;
    private final long reviewThreshold;
    private final long highRiskThreshold;

    public TransactionCountFraudRule(
            Duration window,
            long reviewThreshold,
            long highRiskThreshold) {
        this.window = requirePositive(window, "transaction-count window");
        if (reviewThreshold <= 0) {
            throw new IllegalArgumentException("transaction-count review threshold must be positive");
        }
        if (highRiskThreshold <= reviewThreshold) {
            throw new IllegalArgumentException(
                    "transaction-count highRisk threshold must be greater than review threshold");
        }
        this.reviewThreshold = reviewThreshold;
        this.highRiskThreshold = highRiskThreshold;
    }

    @Override
    public Optional<MatchedFraudRule> evaluate(FraudRuleContext context) {
        Objects.requireNonNull(context, "context must not be null");
        long count = context.velocitySnapshot().transactionCount();
        if (count >= highRiskThreshold) {
            return Optional.of(match(count, highRiskThreshold, FraudRuleSeverity.HIGH_RISK));
        }
        if (count >= reviewThreshold) {
            return Optional.of(match(count, reviewThreshold, FraudRuleSeverity.REVIEW));
        }
        return Optional.empty();
    }

    private MatchedFraudRule match(long count, long threshold, FraudRuleSeverity severity) {
        String evidence = count + " attempts in synthetic " + windowDescription(window)
                + " window met " + severity + " threshold " + threshold;
        return new MatchedFraudRule(RULE_CODE, severity, evidence);
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
