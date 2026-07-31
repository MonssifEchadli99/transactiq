package io.github.monssifechadli99.transactiq.case_management.application.model;

import io.github.monssifechadli99.transactiq.case_management.domain.FraudCase;

public record FraudCaseClaimResult(Outcome outcome, FraudCase fraudCase) {
    public enum Outcome {
        CLAIMED,
        ALREADY_CLAIMED_BY_ANALYST,
        NOT_FOUND,
        ALREADY_ASSIGNED,
        VERSION_CONFLICT,
        NOT_CLAIMABLE
    }
}
