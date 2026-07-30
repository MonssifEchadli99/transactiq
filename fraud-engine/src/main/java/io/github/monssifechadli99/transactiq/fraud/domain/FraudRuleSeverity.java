package io.github.monssifechadli99.transactiq.fraud.domain;

// Only REVIEW and HIGH_RISK: a matched rule always elevates risk, and the highest matched
// severity becomes the overall FraudAssessment. Declared in ascending severity order.
public enum FraudRuleSeverity {
    REVIEW,
    HIGH_RISK
}
