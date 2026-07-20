package io.github.monssifechadli99.transactiq.authorization.adapter.in.web;

import io.github.monssifechadli99.transactiq.authorization.api.AuthorizationRequest;
import io.github.monssifechadli99.transactiq.authorization.api.AuthorizationResponse;
import io.github.monssifechadli99.transactiq.authorization.api.AuthorizationResponse.Status;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationOutcome;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class AuthorizationHttpMapper {

    public AuthorizationCommand toCommand(AuthorizationRequest request) {
        return new AuthorizationCommand(
                request.requestId(),
                request.cardToken(),
                request.merchantId(),
                request.merchantCategoryCode(),
                request.amount(),
                request.currency(),
                request.country(),
                request.channel(),
                request.transactionTime());
    }

    public AuthorizationResponse toResponse(
            AuthorizationCommand command,
            AuthorizationOutcome outcome) {
        return switch (outcome) {
            case AuthorizationOutcome.Approved approved -> new AuthorizationResponse.Approved(
                    command.requestId(),
                    approved.decision());
            case AuthorizationOutcome.Declined declined -> new AuthorizationResponse.Declined(
                    command.requestId(),
                    declined.decision(),
                    declined.declineReason());
        };
    }

    public AuthorizationResponse.Pending toPendingResponse(UUID requestId) {
        return new AuthorizationResponse.Pending(requestId, Status.PENDING);
    }
}
