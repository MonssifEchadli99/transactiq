package io.github.monssifechadli99.transactiq.fraud.domain;

import java.util.Objects;

public record MatchedFraudRule(String ruleCode, FraudRuleSeverity severity, String evidence) {

    public MatchedFraudRule {
        requireNonBlank(ruleCode, "ruleCode");
        Objects.requireNonNull(severity, "severity must not be null");
        requireNonBlank(evidence, "evidence");
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
