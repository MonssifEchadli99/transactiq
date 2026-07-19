package io.github.monssifechadli99.transactiq.authorization.application.service;

import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizeTransactionUseCase;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.AuthorizationLedgerPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.FraudAssessmentPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.NonFraudCheckPort;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationOutcome;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationPolicy;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessment;
import io.github.monssifechadli99.transactiq.authorization.domain.NonFraudCheckResult;
import java.util.Objects;

public final class AuthorizationApplicationService implements AuthorizeTransactionUseCase {

    private final FraudAssessmentPort fraudAssessmentPort;
    private final NonFraudCheckPort nonFraudCheckPort;
    private final AuthorizationLedgerPort authorizationLedgerPort;
    private final AuthorizationPolicy authorizationPolicy;

    public AuthorizationApplicationService(
            FraudAssessmentPort fraudAssessmentPort,
            NonFraudCheckPort nonFraudCheckPort,
            AuthorizationLedgerPort authorizationLedgerPort,
            AuthorizationPolicy authorizationPolicy) {
        this.fraudAssessmentPort = Objects.requireNonNull(fraudAssessmentPort);
        this.nonFraudCheckPort = Objects.requireNonNull(nonFraudCheckPort);
        this.authorizationLedgerPort = Objects.requireNonNull(authorizationLedgerPort);
        this.authorizationPolicy = Objects.requireNonNull(authorizationPolicy);
    }

    @Override
    public AuthorizationOutcome authorize(AuthorizationCommand command) {
        FraudAssessment fraudAssessment = fraudAssessmentPort.assess(command);
        NonFraudCheckResult nonFraudCheckResult = nonFraudCheckPort.check(command);
        AuthorizationOutcome outcome = authorizationPolicy.decide(fraudAssessment, nonFraudCheckResult);

        authorizationLedgerPort.record(command, outcome);

        return outcome;
    }
}
