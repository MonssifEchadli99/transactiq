package io.github.monssifechadli99.transactiq.fraud.domain.velocity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record VelocitySnapshot(
        Instant observedAt,
        long transactionCount,
        Map<String, BigDecimal> rollingAmountsByCurrency,
        Set<String> observedCountries) {

    public VelocitySnapshot {
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        if (transactionCount < 0) {
            throw new IllegalArgumentException("transactionCount must not be negative");
        }
        Objects.requireNonNull(rollingAmountsByCurrency, "rollingAmountsByCurrency must not be null");
        Objects.requireNonNull(observedCountries, "observedCountries must not be null");

        Map<String, BigDecimal> copiedAmounts = new LinkedHashMap<>();
        rollingAmountsByCurrency.forEach((currency, total) -> {
            requireNonBlank(currency, "currency");
            copiedAmounts.put(currency, Objects.requireNonNull(total, "rolling amount must not be null"));
        });
        rollingAmountsByCurrency = Map.copyOf(copiedAmounts);
        observedCountries = Set.copyOf(observedCountries);
    }

    public BigDecimal rollingAmount(String currency) {
        return rollingAmountsByCurrency.getOrDefault(currency, BigDecimal.ZERO);
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
