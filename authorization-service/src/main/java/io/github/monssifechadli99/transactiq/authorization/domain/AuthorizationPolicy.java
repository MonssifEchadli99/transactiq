package io.github.monssifechadli99.transactiq.authorization.domain;

import java.util.Objects;

public final class AuthorizationPolicy {

    public AuthorizationOutcome decide(
            FraudAssessment fraudAssessment,
            NonFraudCheckResult nonFraudCheckResult) {
        Objects.requireNonNull(fraudAssessment, "fraudAssessment must not be null");
        Objects.requireNonNull(nonFraudCheckResult, "nonFraudCheckResult must not be null");

        if (nonFraudCheckResult == NonFraudCheckResult.INSUFFICIENT_FUNDS) {
            boolean fraudCaseRequired = fraudAssessment != FraudAssessment.CLEAR;
            return new AuthorizationOutcome.Declined(
                    DeclineReason.INSUFFICIENT_FUNDS,
                    fraudCaseRequired);
        }

        return switch (fraudAssessment) {
            case CLEAR -> new AuthorizationOutcome.Approved(false);
            case REVIEW -> new AuthorizationOutcome.Approved(true);
            case HIGH_RISK -> new AuthorizationOutcome.Declined(
                    DeclineReason.HIGH_FRAUD_RISK,
                    true);
        };
    }
}
