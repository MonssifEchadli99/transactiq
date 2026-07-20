package io.github.monssifechadli99.transactiq.authorization.application.port.in;

import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationProcessingResult;

public interface AuthorizeTransactionUseCase {

    AuthorizationProcessingResult authorize(AuthorizationCommand command);
}
