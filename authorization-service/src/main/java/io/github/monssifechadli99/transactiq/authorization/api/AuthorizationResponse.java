package io.github.monssifechadli99.transactiq.authorization.api;

import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationDecision;
import io.github.monssifechadli99.transactiq.authorization.domain.DeclineReason;
import java.util.UUID;

public sealed interface AuthorizationResponse
        permits AuthorizationResponse.Approved,
                AuthorizationResponse.Declined,
                AuthorizationResponse.Pending {

    UUID requestId();

    record Approved(
            UUID requestId,
            AuthorizationDecision decision) implements AuthorizationResponse {
    }

    record Declined(
            UUID requestId,
            AuthorizationDecision decision,
            DeclineReason declineReason) implements AuthorizationResponse {
    }

    record Pending(
            UUID requestId,
            Status status) implements AuthorizationResponse {
    }

    enum Status {
        PENDING
    }
}
