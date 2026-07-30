package io.github.monssifechadli99.transactiq.fraud.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record FraudAssessmentRequest(
        UUID requestId,
        String cardToken,
        String merchantId,
        String merchantCategoryCode,
        BigDecimal amount,
        String currency,
        String country,
        FraudChannel channel,
        Instant transactionTime) {

    public FraudAssessmentRequest {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(cardToken, "cardToken must not be null");
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(merchantCategoryCode, "merchantCategoryCode must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(country, "country must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(transactionTime, "transactionTime must not be null");
    }
}
