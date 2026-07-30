package io.github.monssifechadli99.transactiq.authorization.configuration;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AuthorizationOutboxPublisherPropertiesTest {

    @Test
    void rejectsNonPositiveBatchSize() {
        assertThrows(IllegalArgumentException.class, () -> properties(
                0, Duration.ofSeconds(1), Duration.ofSeconds(10)));
    }

    @Test
    void rejectsNonPositiveBackoff() {
        assertThrows(IllegalArgumentException.class, () -> properties(
                10, Duration.ZERO, Duration.ofSeconds(10)));
    }

    @Test
    void rejectsMaximumBackoffBelowInitialBackoff() {
        assertThrows(IllegalArgumentException.class, () -> properties(
                10, Duration.ofSeconds(10), Duration.ofSeconds(1)));
    }

    private static AuthorizationOutboxPublisherProperties properties(
            int batchSize, Duration initialBackoff, Duration maximumBackoff) {
        return new AuthorizationOutboxPublisherProperties(
                true,
                "transactiq.authorization.completed.v1",
                batchSize,
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                initialBackoff,
                maximumBackoff);
    }
}
