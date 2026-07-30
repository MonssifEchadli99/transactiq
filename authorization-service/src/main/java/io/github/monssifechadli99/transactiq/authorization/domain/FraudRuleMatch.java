package io.github.monssifechadli99.transactiq.authorization.domain;

import java.util.Objects;

public record FraudRuleMatch(
        String ruleCode,
        FraudRuleSeverity severity,
        String evidence,
        int scoreContribution) {

    public FraudRuleMatch {
        requireText(ruleCode, "ruleCode");
        Objects.requireNonNull(severity, "severity must not be null");
        requireText(evidence, "evidence");
        if (scoreContribution < 1 || scoreContribution > 100) {
            throw new IllegalArgumentException("scoreContribution must be between 1 and 100");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
