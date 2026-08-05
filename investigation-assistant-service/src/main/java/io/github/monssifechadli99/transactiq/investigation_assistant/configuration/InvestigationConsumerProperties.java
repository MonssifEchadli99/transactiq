package io.github.monssifechadli99.transactiq.investigation_assistant.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("investigation-assistant.consumer")
public record InvestigationConsumerProperties(
        String topic,
        String groupId,
        String dltTopic,
        int topicPartitions,
        Duration retryInterval,
        long retryAttempts) {
}
