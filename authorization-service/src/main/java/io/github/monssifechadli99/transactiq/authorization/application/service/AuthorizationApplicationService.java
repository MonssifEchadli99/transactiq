package io.github.monssifechadli99.transactiq.authorization.application.service;

import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationProcessingResult;
import io.github.monssifechadli99.transactiq.authorization.application.model.FraudAssessmentConflictException;
import io.github.monssifechadli99.transactiq.authorization.application.model.IdempotencyClaimResult;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizeTransactionUseCase;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.FraudAssessmentPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.IdempotencyClaimPort;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationOutcome;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessmentResult;
import java.util.Objects;

public final class AuthorizationApplicationService implements AuthorizeTransactionUseCase {

    private final IdempotencyClaimPort idempotencyClaimPort;
    private final FraudAssessmentPort fraudAssessmentPort;
    private final AuthorizationCompletionService authorizationCompletionService;

    public AuthorizationApplicationService(
            IdempotencyClaimPort idempotencyClaimPort,
            FraudAssessmentPort fraudAssessmentPort,
            AuthorizationCompletionService authorizationCompletionService) {
        this.idempotencyClaimPort = Objects.requireNonNull(
                idempotencyClaimPort, "idempotencyClaimPort must not be null");
        this.fraudAssessmentPort = Objects.requireNonNull(
                fraudAssessmentPort, "fraudAssessmentPort must not be null");
        this.authorizationCompletionService = Objects.requireNonNull(
                authorizationCompletionService,
                "authorizationCompletionService must not be null");
    }

    @Override
    public AuthorizationProcessingResult authorize(AuthorizationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        IdempotencyClaimResult claimResult = idempotencyClaimPort.claim(command);

        return switch (claimResult) {
            case IdempotencyClaimResult.Completed completed ->
                    new AuthorizationProcessingResult.Completed(
                            completed.outcome(), completed.fraudAssessment());
            case IdempotencyClaimResult.Pending pending ->
                    new AuthorizationProcessingResult.Pending(command.requestId());
            case IdempotencyClaimResult.Conflict conflict ->
                    new AuthorizationProcessingResult.Conflict(command.requestId());
            case IdempotencyClaimResult.Claimed claimed -> completeClaimedRequest(command);
        };
    }

    private AuthorizationProcessingResult completeClaimedRequest(AuthorizationCommand command) {
        try {
            FraudAssessmentResult fraudAssessment = fraudAssessmentPort.assess(command);
            AuthorizationOutcome outcome = authorizationCompletionService.complete(
                    command, fraudAssessment);
            return new AuthorizationProcessingResult.Completed(outcome, fraudAssessment);
        } catch (FraudAssessmentConflictException conflict) {
            releaseClaim(command);
            return new AuthorizationProcessingResult.Conflict(command.requestId());
        } catch (RuntimeException | Error failure) {
            releaseClaimPreserving(command, failure);
            throw failure;
        }
    }

    private void releaseClaim(AuthorizationCommand command) {
        if (!idempotencyClaimPort.releasePending(command.requestId())) {
            throw new IllegalStateException("Pending authorization claim could not be released");
        }
    }

    private void releaseClaimPreserving(AuthorizationCommand command, Throwable originalFailure) {
        try {
            idempotencyClaimPort.releasePending(command.requestId());
        } catch (RuntimeException | Error releaseFailure) {
            originalFailure.addSuppressed(releaseFailure);
        }
    }
}
