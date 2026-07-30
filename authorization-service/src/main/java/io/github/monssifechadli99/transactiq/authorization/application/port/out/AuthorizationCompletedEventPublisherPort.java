package io.github.monssifechadli99.transactiq.authorization.application.port.out;

import io.github.monssifechadli99.transactiq.authorization.application.model.ClaimedAuthorizationOutboxEvent;

public interface AuthorizationCompletedEventPublisherPort {

    void publish(ClaimedAuthorizationOutboxEvent event);
}
