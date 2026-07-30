package io.github.monssifechadli99.transactiq.authorization.adapter.out.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationChannel;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DeterministicFraudAssessmentAdapterTest {

    private final DeterministicFraudAssessmentAdapter adapter =
            new DeterministicFraudAssessmentAdapter();

    @ParameterizedTest
    @CsvSource({
        "merchant-review, REVIEW",
        "merchant-high-risk, HIGH_RISK",
        "merchant-standard, CLEAR"
    })
    void mapsSyntheticMerchantId(String merchantId, FraudAssessment expected) {
        assertEquals(expected, adapter.assess(command(merchantId)).assessment());
    }

    private static AuthorizationCommand command(String merchantId) {
        return new AuthorizationCommand(
                UUID.fromString("d5e75b60-a263-4f76-b5d0-a35f1a09bc67"),
                "tok_A1B2C3D4",
                merchantId,
                "5411",
                new BigDecimal("42.50"),
                "EUR",
                "DE",
                AuthorizationChannel.ECOMMERCE,
                Instant.parse("2026-07-19T10:15:30Z"));
    }
}
