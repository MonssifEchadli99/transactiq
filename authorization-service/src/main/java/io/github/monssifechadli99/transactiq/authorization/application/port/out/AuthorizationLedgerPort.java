package io.github.monssifechadli99.transactiq.authorization.application.port.out;

import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationOutcome;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessment;
import io.github.monssifechadli99.transactiq.authorization.domain.NonFraudCheckResult;

public interface AuthorizationLedgerPort {

    void record(
            AuthorizationCommand command,
            FraudAssessment fraudAssessment,
            NonFraudCheckResult nonFraudCheckResult,
            AuthorizationOutcome outcome);
}
