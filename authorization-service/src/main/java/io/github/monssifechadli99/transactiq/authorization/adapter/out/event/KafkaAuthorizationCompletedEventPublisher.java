package io.github.monssifechadli99.transactiq.authorization.adapter.out.event;

import io.github.monssifechadli99.transactiq.authorization.application.model.ClaimedAuthorizationOutboxEvent;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.AuthorizationCompletedEventPublisherPort;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import org.springframework.kafka.core.KafkaTemplate;

public final class KafkaAuthorizationCompletedEventPublisher
        implements AuthorizationCompletedEventPublisherPort {

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final String topic;

    public KafkaAuthorizationCompletedEventPublisher(
            KafkaTemplate<Object, Object> kafkaTemplate, String topic) {
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate must not be null");
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        this.topic = topic;
    }

    @Override
    public void publish(ClaimedAuthorizationOutboxEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        try {
            kafkaTemplate.send(topic, event.partitionKey(), event.payload()).get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AuthorizationEventPublicationException(interrupted);
        } catch (ExecutionException publicationFailure) {
            throw new AuthorizationEventPublicationException(publicationFailure.getCause());
        }
    }

    private static final class AuthorizationEventPublicationException extends RuntimeException {

        private AuthorizationEventPublicationException(Throwable cause) {
            super("Authorization-completed event publication failed", cause);
        }
    }
}
