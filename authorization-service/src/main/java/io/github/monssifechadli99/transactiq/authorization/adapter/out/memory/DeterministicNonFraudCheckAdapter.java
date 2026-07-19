package io.github.monssifechadli99.transactiq.authorization.adapter.out.memory;

import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.NonFraudCheckPort;
import io.github.monssifechadli99.transactiq.authorization.domain.NonFraudCheckResult;
import java.util.Objects;

public final class DeterministicNonFraudCheckAdapter implements NonFraudCheckPort {

    private static final String INSUFFICIENT_FUNDS_CARD_TOKEN = "tok_insufficient01";

    @Override
    public NonFraudCheckResult check(AuthorizationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String cardToken = Objects.requireNonNull(
                command.cardToken(),
                "cardToken must not be null");

        if (INSUFFICIENT_FUNDS_CARD_TOKEN.equals(cardToken)) {
            return NonFraudCheckResult.INSUFFICIENT_FUNDS;
        }
        return NonFraudCheckResult.PASSED;
    }
}
