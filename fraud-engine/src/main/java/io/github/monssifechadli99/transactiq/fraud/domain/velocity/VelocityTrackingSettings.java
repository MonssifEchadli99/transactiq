package io.github.monssifechadli99.transactiq.fraud.domain.velocity;

import java.time.Duration;
import java.util.Objects;

public record VelocityTrackingSettings(
        Duration transactionCountWindow,
        Duration rollingAmountWindow,
        Duration countrySwitchWindow,
        Duration deduplicationRetention) {

    public VelocityTrackingSettings {
        requirePositive(transactionCountWindow, "transaction-count window");
        requirePositive(rollingAmountWindow, "rolling-amount window");
        requirePositive(countrySwitchWindow, "country-switch window");
        requirePositive(deduplicationRetention, "deduplication retention");

        Duration longestWindow = longerOf(
                longerOf(transactionCountWindow, rollingAmountWindow),
                countrySwitchWindow);
        if (deduplicationRetention.compareTo(longestWindow) <= 0) {
            throw new IllegalArgumentException(
                    "deduplication retention must be longer than every velocity window");
        }
    }

    private static void requirePositive(Duration value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        if (value.toMillis() == 0) {
            throw new IllegalArgumentException(fieldName + " must be at least one millisecond");
        }
    }

    private static Duration longerOf(Duration first, Duration second) {
        return first.compareTo(second) >= 0 ? first : second;
    }
}
