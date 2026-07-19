package io.github.monssifechadli99.transactiq.authorization.adapter.out.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationChannel;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.domain.NonFraudCheckResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DeterministicNonFraudCheckAdapterTest {

    private final DeterministicNonFraudCheckAdapter adapter =
            new DeterministicNonFraudCheckAdapter();

    @ParameterizedTest
    @CsvSource({
        "tok_insufficient01, INSUFFICIENT_FUNDS",
        "tok_A1B2C3D4, PASSED"
    })
    void mapsSyntheticCardToken(String cardToken, NonFraudCheckResult expected) {
        assertEquals(expected, adapter.check(command(cardToken)));
    }

    private static AuthorizationCommand command(String cardToken) {
        return new AuthorizationCommand(
                UUID.fromString("d5e75b60-a263-4f76-b5d0-a35f1a09bc67"),
                cardToken,
                "merchant-standard",
                "5411",
                new BigDecimal("42.50"),
                "EUR",
                "DE",
                AuthorizationChannel.ECOMMERCE,
                Instant.parse("2026-07-19T10:15:30Z"));
    }
}
