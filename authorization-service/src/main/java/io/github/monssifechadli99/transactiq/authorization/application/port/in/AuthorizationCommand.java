package io.github.monssifechadli99.transactiq.authorization.application.port.in;

import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationChannel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AuthorizationCommand(
        UUID requestId,
        String cardToken,
        String merchantId,
        String merchantCategoryCode,
        BigDecimal amount,
        String currency,
        String country,
        AuthorizationChannel channel,
        Instant transactionTime) {
}
