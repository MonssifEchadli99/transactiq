package io.github.monssifechadli99.transactiq.authorization.application.port.out;

import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.domain.NonFraudCheckResult;

public interface NonFraudCheckPort {

    NonFraudCheckResult check(AuthorizationCommand command);
}
