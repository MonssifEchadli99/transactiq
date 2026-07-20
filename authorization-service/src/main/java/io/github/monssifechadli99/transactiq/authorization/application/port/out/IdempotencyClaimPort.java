package io.github.monssifechadli99.transactiq.authorization.application.port.out;

import io.github.monssifechadli99.transactiq.authorization.application.model.IdempotencyClaimResult;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import java.util.UUID;

public interface IdempotencyClaimPort {

    IdempotencyClaimResult claim(AuthorizationCommand command);

    boolean releasePending(UUID requestId);
}
