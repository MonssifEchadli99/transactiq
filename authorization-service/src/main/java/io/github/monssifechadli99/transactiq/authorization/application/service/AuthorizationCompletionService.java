package io.github.monssifechadli99.transactiq.authorization.application.service;

import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.AuthorizationLedgerPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.AuthorizationCompletedEventOutboxPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.NonFraudCheckPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.TransactionExecutorPort;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationOutcome;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationPolicy;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessmentResult;
import io.github.monssifechadli99.transactiq.authorization.domain.NonFraudCheckResult;
import java.util.Objects;

public final class AuthorizationCompletionService {

    private final TransactionExecutorPort transactionExecutor;
    private final NonFraudCheckPort nonFraudCheckPort;
    private final AuthorizationLedgerPort authorizationLedgerPort;
    private final AuthorizationCompletedEventOutboxPort authorizationCompletedEventOutboxPort;
    private final AuthorizationPolicy authorizationPolicy;

    public AuthorizationCompletionService(
            TransactionExecutorPort transactionExecutor,
            NonFraudCheckPort nonFraudCheckPort,
            AuthorizationLedgerPort authorizationLedgerPort,
            AuthorizationCompletedEventOutboxPort authorizationCompletedEventOutboxPort,
            AuthorizationPolicy authorizationPolicy) {
        this.transactionExecutor = Objects.requireNonNull(
                transactionExecutor, "transactionExecutor must not be null");
        this.nonFraudCheckPort = Objects.requireNonNull(
                nonFraudCheckPort, "nonFraudCheckPort must not be null");
        this.authorizationLedgerPort = Objects.requireNonNull(
                authorizationLedgerPort, "authorizationLedgerPort must not be null");
        this.authorizationCompletedEventOutboxPort = Objects.requireNonNull(
                authorizationCompletedEventOutboxPort,
                "authorizationCompletedEventOutboxPort must not be null");
        this.authorizationPolicy = Objects.requireNonNull(
                authorizationPolicy, "authorizationPolicy must not be null");
    }

    public AuthorizationOutcome complete(
            AuthorizationCommand command, FraudAssessmentResult fraudAssessment) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(fraudAssessment, "fraudAssessment must not be null");

        return transactionExecutor.execute(() -> completeWithinTransaction(command, fraudAssessment));
    }

    private AuthorizationOutcome completeWithinTransaction(
            AuthorizationCommand command, FraudAssessmentResult fraudAssessment) {
        NonFraudCheckResult nonFraudCheckResult = nonFraudCheckPort.check(command);
        AuthorizationOutcome outcome = authorizationPolicy.decide(
                fraudAssessment.assessment(), nonFraudCheckResult);
        authorizationLedgerPort.record(
                command, fraudAssessment, nonFraudCheckResult, outcome);
        authorizationCompletedEventOutboxPort.append(
                command, fraudAssessment, nonFraudCheckResult, outcome);
        return outcome;
    }
}
