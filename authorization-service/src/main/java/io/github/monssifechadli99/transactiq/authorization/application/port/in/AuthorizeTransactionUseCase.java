package io.github.monssifechadli99.transactiq.authorization.application.port.in;

import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationOutcome;

public interface AuthorizeTransactionUseCase {

    AuthorizationOutcome authorize(AuthorizationCommand command);
}
