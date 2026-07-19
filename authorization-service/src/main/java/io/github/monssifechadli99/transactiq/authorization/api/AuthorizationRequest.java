package io.github.monssifechadli99.transactiq.authorization.api;

import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationChannel;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AuthorizationRequest(
        @NotNull UUID requestId,
        @NotNull @Pattern(regexp = "tok_[A-Za-z0-9]{8,60}") String cardToken,
        @NotBlank @Size(max = 64) String merchantId,
        @NotBlank @Pattern(regexp = "[0-9]{4}") String merchantCategoryCode,
        @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotBlank @Pattern(regexp = "[A-Z]{2}") String country,
        @NotNull AuthorizationChannel channel,
        @NotNull Instant transactionTime) {
}
