package io.github.monssifechadli99.transactiq.case_management.application.model;

import io.github.monssifechadli99.transactiq.case_management.domain.FraudCase;

public record FraudCaseResolutionResult(Outcome outcome, FraudCase fraudCase) {
    public enum Outcome {
        RESOLVED,
        ALREADY_RESOLVED_IDENTICALLY,
        ALREADY_RESOLVED_DIFFERENTLY,
        NOT_IN_REVIEW,
        NOT_ASSIGNED_TO_ANALYST,
        VERSION_CONFLICT,
        NOT_FOUND
    }
}
