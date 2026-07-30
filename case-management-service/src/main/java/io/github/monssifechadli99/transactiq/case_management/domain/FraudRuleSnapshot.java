package io.github.monssifechadli99.transactiq.case_management.domain;

import java.util.Objects;

public record FraudRuleSnapshot(
        String ruleCode,
        FraudRuleSeverity severity,
        String evidence,
        int scoreContribution) {

    public FraudRuleSnapshot {
        requireText(ruleCode, "ruleCode");
        Objects.requireNonNull(severity, "severity must not be null");
        requireText(evidence, "evidence");
        if (scoreContribution < 1 || scoreContribution > 100) {
            throw new InvalidAuthorizationEventException(
                    "scoreContribution must be between 1 and 100");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidAuthorizationEventException(field + " must not be blank");
        }
    }
}
