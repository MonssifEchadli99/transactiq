package io.github.monssifechadli99.transactiq.authorization.configuration;

import io.github.monssifechadli99.transactiq.authorization.adapter.out.event.JdbcAuthorizationOutboxStore;
import io.github.monssifechadli99.transactiq.authorization.adapter.out.event.KafkaAuthorizationCompletedEventPublisher;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.AuthorizationCompletedEventPublisherPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.AuthorizationOutboxStorePort;
import io.github.monssifechadli99.transactiq.authorization.application.service.AuthorizationOutboxRelay;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(AuthorizationOutboxPublisherProperties.class)
public class AuthorizationOutboxPublisherConfiguration {

    @Bean
    JdbcAuthorizationOutboxStore authorizationOutboxStore(
            JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
        return new JdbcAuthorizationOutboxStore(
                jdbcClient, new TransactionTemplate(transactionManager));
    }

    @Bean
    @ConditionalOnProperty(
            name = "authorization.outbox.publisher.enabled",
            havingValue = "true",
            matchIfMissing = true)
    KafkaAuthorizationCompletedEventPublisher authorizationCompletedEventPublisher(
            KafkaTemplate<Object, Object> kafkaTemplate,
            AuthorizationOutboxPublisherProperties properties) {
        return new KafkaAuthorizationCompletedEventPublisher(kafkaTemplate, properties.topic());
    }

    @Bean
    @ConditionalOnProperty(
            name = "authorization.outbox.publisher.enabled",
            havingValue = "true",
            matchIfMissing = true)
    ScheduledAuthorizationOutboxRelay scheduledAuthorizationOutboxRelay(
            AuthorizationOutboxStorePort outboxStore,
            AuthorizationCompletedEventPublisherPort eventPublisher,
            AuthorizationOutboxPublisherProperties properties,
            Clock authorizationEventClock) {
        return new ScheduledAuthorizationOutboxRelay(new AuthorizationOutboxRelay(
                outboxStore, eventPublisher, properties, authorizationEventClock));
    }

    static final class ScheduledAuthorizationOutboxRelay {

        private final AuthorizationOutboxRelay relay;

        private ScheduledAuthorizationOutboxRelay(AuthorizationOutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(
                initialDelayString = "${authorization.outbox.publisher.poll-interval:1s}",
                fixedDelayString = "${authorization.outbox.publisher.poll-interval:1s}")
        void publishDue() {
            relay.publishDue();
        }
    }
}
