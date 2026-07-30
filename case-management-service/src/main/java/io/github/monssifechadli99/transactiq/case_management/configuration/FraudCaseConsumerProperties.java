package io.github.monssifechadli99.transactiq.case_management.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fraud-case.consumer")
public record FraudCaseConsumerProperties(
        String topic,
        String groupId,
        Duration retryInterval) {

    public FraudCaseConsumerProperties {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("fraud-case consumer topic must not be blank");
        }
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("fraud-case consumer groupId must not be blank");
        }
        if (retryInterval == null || retryInterval.isNegative() || retryInterval.isZero()) {
            throw new IllegalArgumentException(
                    "fraud-case consumer retryInterval must be positive");
        }
    }
}
