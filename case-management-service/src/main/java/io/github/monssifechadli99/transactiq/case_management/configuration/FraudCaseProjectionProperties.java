package io.github.monssifechadli99.transactiq.case_management.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fraud-case.projection")
public record FraudCaseProjectionProperties(
        String topic, int topicPartitions, int batchSize, Duration pollInterval,
        Duration leaseDuration, Duration retryInitialBackoff, Duration retryMaximumBackoff,
        boolean bootstrapEnabled, int bootstrapBatchSize, String environment,
        Duration producerOperationTimeout) {
    public String transactionalId(int partition) {
        return environment + "." + topic + ".p" + partition;
    }
}
