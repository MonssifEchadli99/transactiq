package io.github.monssifechadli99.transactiq.authorization.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("authorization.outbox.publisher")
public record AuthorizationOutboxPublisherProperties(
        boolean enabled,
        String topic,
        int batchSize,
        Duration pollInterval,
        Duration leaseDuration,
        Duration initialRetryBackoff,
        Duration maxRetryBackoff) {

    public AuthorizationOutboxPublisherProperties {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("authorization outbox topic must not be blank");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("authorization outbox batchSize must be positive");
        }
        requirePositive(pollInterval, "pollInterval");
        requirePositive(leaseDuration, "leaseDuration");
        requirePositive(initialRetryBackoff, "initialRetryBackoff");
        requirePositive(maxRetryBackoff, "maxRetryBackoff");
        if (maxRetryBackoff.compareTo(initialRetryBackoff) < 0) {
            throw new IllegalArgumentException(
                    "authorization outbox maxRetryBackoff must not be less than initialRetryBackoff");
        }
    }

    private static void requirePositive(Duration value, String propertyName) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "authorization outbox " + propertyName + " must be positive");
        }
    }
}
