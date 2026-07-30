package io.github.monssifechadli99.transactiq.authorization.application.model;

import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationOutcome;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessmentResult;
import java.util.Objects;

public sealed interface IdempotencyClaimResult
        permits IdempotencyClaimResult.Claimed,
                IdempotencyClaimResult.Pending,
                IdempotencyClaimResult.Completed,
                IdempotencyClaimResult.Conflict {

    record Claimed() implements IdempotencyClaimResult {}

    record Pending() implements IdempotencyClaimResult {}

    record Completed(
            AuthorizationOutcome outcome,
            FraudAssessmentResult fraudAssessment) implements IdempotencyClaimResult {

        public Completed {
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(fraudAssessment, "fraudAssessment must not be null");
        }
    }

    record Conflict() implements IdempotencyClaimResult {}
}
