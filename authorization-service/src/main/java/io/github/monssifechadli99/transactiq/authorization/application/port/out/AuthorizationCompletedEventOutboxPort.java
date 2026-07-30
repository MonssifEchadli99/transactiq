package io.github.monssifechadli99.transactiq.authorization.application.port.out;

import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationOutcome;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessmentResult;
import io.github.monssifechadli99.transactiq.authorization.domain.NonFraudCheckResult;

public interface AuthorizationCompletedEventOutboxPort {

    void append(
            AuthorizationCommand command,
            FraudAssessmentResult fraudAssessment,
            NonFraudCheckResult nonFraudCheckResult,
            AuthorizationOutcome outcome);
}
