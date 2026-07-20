package io.github.monssifechadli99.transactiq.authorization.application.model;

import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationOutcome;
import java.util.Objects;
import java.util.UUID;

public sealed interface AuthorizationProcessingResult
        permits AuthorizationProcessingResult.Completed,
                AuthorizationProcessingResult.Pending,
                AuthorizationProcessingResult.Conflict {

    record Completed(AuthorizationOutcome outcome) implements AuthorizationProcessingResult {

        public Completed {
            Objects.requireNonNull(outcome, "outcome must not be null");
        }
    }

    record Pending(UUID requestId) implements AuthorizationProcessingResult {

        public Pending {
            Objects.requireNonNull(requestId, "requestId must not be null");
        }
    }

    record Conflict(UUID requestId) implements AuthorizationProcessingResult {

        public Conflict {
            Objects.requireNonNull(requestId, "requestId must not be null");
        }
    }
}
