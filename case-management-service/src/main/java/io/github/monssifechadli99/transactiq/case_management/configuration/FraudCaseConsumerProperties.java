package io.github.monssifechadli99.transactiq.case_management.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fraud-case.consumer")
public record FraudCaseConsumerProperties(
        String topic,
        String groupId,
        Duration retryInitialInterval,
        Duration retryMaximumInterval,
        int unexpectedTotalAttempts,
        Duration recoveryRetryInterval,
        String dltTopic,
        int topicPartitions) {

    public FraudCaseConsumerProperties {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("fraud-case consumer topic must not be blank");
        }
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("fraud-case consumer groupId must not be blank");
        }
        requirePositive(retryInitialInterval, "retryInitialInterval");
        requirePositive(retryMaximumInterval, "retryMaximumInterval");
        if (retryMaximumInterval.compareTo(retryInitialInterval) < 0) {
            throw new IllegalArgumentException(
                    "fraud-case consumer retryMaximumInterval must not be less than retryInitialInterval");
        }
        if (unexpectedTotalAttempts < 1) {
            throw new IllegalArgumentException(
                    "fraud-case consumer unexpectedTotalAttempts must be positive");
        }
        requirePositive(recoveryRetryInterval, "recoveryRetryInterval");
        if (dltTopic == null || dltTopic.isBlank()) {
            throw new IllegalArgumentException("fraud-case consumer dltTopic must not be blank");
        }
        if (topicPartitions <= 0) {
            throw new IllegalArgumentException("fraud-case consumer topicPartitions must be positive");
        }
    }

    private static void requirePositive(Duration value, String propertyName) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(
                    "fraud-case consumer " + propertyName + " must be positive");
        }
    }
}
