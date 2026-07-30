package io.github.monssifechadli99.transactiq.fraud.domain;

import java.util.Objects;

public record ScoredFraudRuleMatch(
        String ruleCode,
        FraudRuleSeverity severity,
        String evidence,
        int scoreContribution) {

    public ScoredFraudRuleMatch {
        if (ruleCode == null || ruleCode.isBlank()) {
            throw new IllegalArgumentException("ruleCode must not be blank");
        }
        Objects.requireNonNull(severity, "severity must not be null");
        if (evidence == null || evidence.isBlank()) {
            throw new IllegalArgumentException("evidence must not be blank");
        }
        if (scoreContribution < 1 || scoreContribution > 100) {
            throw new IllegalArgumentException("scoreContribution must be between 1 and 100");
        }
    }
}
