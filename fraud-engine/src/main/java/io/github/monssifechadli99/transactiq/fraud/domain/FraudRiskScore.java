package io.github.monssifechadli99.transactiq.fraud.domain;

import java.util.List;
import java.util.Objects;

public record FraudRiskScore(int value, List<ScoredFraudRuleMatch> matchedRules) {

    public FraudRiskScore {
        Objects.requireNonNull(matchedRules, "matchedRules must not be null");
        matchedRules = List.copyOf(matchedRules);
        int expectedValue = (int) Math.min(
                100L,
                matchedRules.stream()
                        .mapToLong(ScoredFraudRuleMatch::scoreContribution)
                        .sum());
        if (value != expectedValue) {
            throw new IllegalArgumentException(
                    "risk score must equal the capped sum of match contributions: expected "
                            + expectedValue + " but was " + value);
        }
    }
}
