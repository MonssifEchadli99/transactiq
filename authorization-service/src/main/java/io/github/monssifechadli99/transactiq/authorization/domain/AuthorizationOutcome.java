package io.github.monssifechadli99.transactiq.authorization.domain;

import java.util.Objects;

public sealed interface AuthorizationOutcome
        permits AuthorizationOutcome.Approved, AuthorizationOutcome.Declined {

    AuthorizationDecision decision();

    boolean fraudCaseRequired();

    record Approved(boolean fraudCaseRequired) implements AuthorizationOutcome {

        @Override
        public AuthorizationDecision decision() {
            return AuthorizationDecision.APPROVED;
        }
    }

    record Declined(DeclineReason declineReason, boolean fraudCaseRequired) implements AuthorizationOutcome {

        public Declined {
            Objects.requireNonNull(declineReason, "declineReason must not be null");
        }

        @Override
        public AuthorizationDecision decision() {
            return AuthorizationDecision.DECLINED;
        }
    }
}
