package io.github.monssifechadli99.transactiq.case_management.configuration;

import io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka.AuthorizationCompletedEventConsumer;
import io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka.AuthorizationCompletedEventParser;
import io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka.FraudCaseRetryListener;
import io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka.KafkaFailurePolicy;
import io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka.RecoveryHeaders;
import io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka.RecoveryRetryingRecoverer;
import io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka.TransactIqDeadLetterPublishingRecoverer;
import io.github.monssifechadli99.transactiq.case_management.adapter.in.web.FraudCaseApiMapper;
import io.github.monssifechadli99.transactiq.case_management.adapter.out.jdbc.JdbcFraudCaseStore;
import io.github.monssifechadli99.transactiq.case_management.adapter.out.jdbc.JdbcFraudCaseLifecycleStore;
import io.github.monssifechadli99.transactiq.case_management.application.service.FraudCaseCursorCodec;
import io.github.monssifechadli99.transactiq.case_management.application.port.out.FraudCaseStore;
import io.github.monssifechadli99.transactiq.case_management.application.service.FraudCaseCreationService;
import io.github.monssifechadli99.transactiq.case_management.application.service.FraudCaseLifecycleService;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseClaimPolicy;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseResolutionPolicy;
import io.github.monssifechadli99.transactiq.case_management.projection.FraudCaseProjectionMapper;
import io.github.monssifechadli99.transactiq.case_management.projection.FraudCaseProjectionOutbox;
import io.github.monssifechadli99.transactiq.case_management.projection.FraudCaseProjectionRelay;
import io.github.monssifechadli99.transactiq.case_management.projection.FraudCaseProjectionScheduler;
import io.github.monssifechadli99.transactiq.case_management.projection.JdbcProjectionDeliveryStore;
import io.github.monssifechadli99.transactiq.case_management.projection.FraudCaseProjectionBootstrap;
import io.github.monssifechadli99.transactiq.case_management.projection.FraudCaseProjectionBootstrapRunner;
import io.github.monssifechadli99.transactiq.case_management.projection.KafkaProjectionTransactionalProducerFactory;
import io.github.monssifechadli99.transactiq.case_management.projection.ProjectionTransactionalProducerFactory;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.ConfigurableApplicationContext;
import org.apache.kafka.common.config.TopicConfig;

@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableScheduling
@EnableConfigurationProperties({FraudCaseConsumerProperties.class, FraudCaseProjectionProperties.class})
public class FraudCaseConfiguration {

    @Bean
    Clock fraudCaseClock() {
        return Clock.systemUTC();
    }

    @Bean
    AuthorizationCompletedEventParser authorizationCompletedEventParser() {
        return new AuthorizationCompletedEventParser();
    }

    @Bean
    JdbcFraudCaseStore fraudCaseStore(
            JdbcClient jdbcClient,
            PlatformTransactionManager transactionManager,
            Clock fraudCaseClock,
            FraudCaseProjectionOutbox projectionOutbox) {
        return new JdbcFraudCaseStore(
                jdbcClient,
                new TransactionTemplate(transactionManager),
                fraudCaseClock,
                UUID::randomUUID,
                projectionOutbox);
    }

    @Bean
    FraudCaseCreationService fraudCaseCreationService(FraudCaseStore fraudCaseStore) {
        return new FraudCaseCreationService(fraudCaseStore);
    }

    @Bean
    JdbcFraudCaseLifecycleStore fraudCaseLifecycleStore(
            JdbcClient jdbcClient,
            PlatformTransactionManager transactionManager,
            Clock fraudCaseClock,
            FraudCaseClaimPolicy claimPolicy,
            FraudCaseResolutionPolicy resolutionPolicy,
            FraudCaseProjectionOutbox projectionOutbox) {
        return new JdbcFraudCaseLifecycleStore(
                jdbcClient,
                new TransactionTemplate(transactionManager),
                fraudCaseClock,
                UUID::randomUUID,
                claimPolicy,
                resolutionPolicy,
                projectionOutbox);
    }

    @Bean FraudCaseProjectionMapper fraudCaseProjectionMapper() { return new FraudCaseProjectionMapper(); }

    @Bean FraudCaseProjectionOutbox fraudCaseProjectionOutbox(
            JdbcClient jdbcClient, FraudCaseProjectionMapper mapper) {
        return new FraudCaseProjectionOutbox(jdbcClient, mapper, UUID::randomUUID);
    }

    @Bean JdbcProjectionDeliveryStore jdbcProjectionDeliveryStore(
            JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
        return new JdbcProjectionDeliveryStore(jdbcClient, new TransactionTemplate(transactionManager));
    }

    @Bean ProjectionTransactionalProducerFactory projectionTransactionalProducerFactory(
            ProducerFactory<Object,Object> producerFactory, FraudCaseProjectionProperties properties) {
        return new KafkaProjectionTransactionalProducerFactory(
                producerFactory.getConfigurationProperties(), properties.producerOperationTimeout());
    }

    @Bean FraudCaseProjectionRelay fraudCaseProjectionRelay(
            JdbcProjectionDeliveryStore store, ProjectionTransactionalProducerFactory producers,
            FraudCaseProjectionProperties properties, Clock fraudCaseClock) {
        return new FraudCaseProjectionRelay(store, producers, properties, fraudCaseClock);
    }

    @Bean
    @ConditionalOnExpression("'${spring.kafka.listener.auto-startup:true}' == 'true' and '${fraud-case.projection.bootstrap-enabled:false}' == 'false'")
    FraudCaseProjectionScheduler fraudCaseProjectionScheduler(FraudCaseProjectionRelay relay) {
        return new FraudCaseProjectionScheduler(relay);
    }

    @Bean
    @ConditionalOnProperty(name="fraud-case.projection.bootstrap-enabled",havingValue="true")
    FraudCaseProjectionBootstrap fraudCaseProjectionBootstrap(
            JdbcClient jdbcClient, JdbcFraudCaseLifecycleStore cases,
            FraudCaseProjectionOutbox outbox, FraudCaseProjectionProperties properties) {
        return new FraudCaseProjectionBootstrap(jdbcClient,cases,outbox,properties.bootstrapBatchSize());
    }

    @Bean
    @ConditionalOnProperty(name="fraud-case.projection.bootstrap-enabled",havingValue="true")
    FraudCaseProjectionBootstrapRunner fraudCaseProjectionBootstrapRunner(
            FraudCaseProjectionBootstrap bootstrap, ConfigurableApplicationContext context) {
        return new FraudCaseProjectionBootstrapRunner(bootstrap, context);
    }

    @Bean
    FraudCaseClaimPolicy fraudCaseClaimPolicy() {
        return new FraudCaseClaimPolicy();
    }

    @Bean
    FraudCaseResolutionPolicy fraudCaseResolutionPolicy() {
        return new FraudCaseResolutionPolicy();
    }

    @Bean
    FraudCaseCursorCodec fraudCaseCursorCodec() {
        return new FraudCaseCursorCodec();
    }

    @Bean
    FraudCaseApiMapper fraudCaseApiMapper() {
        return new FraudCaseApiMapper();
    }

    @Bean
    FraudCaseLifecycleService fraudCaseLifecycleService(
            JdbcFraudCaseLifecycleStore store, FraudCaseCursorCodec cursorCodec) {
        return new FraudCaseLifecycleService(store, cursorCodec);
    }

    @Bean
    AuthorizationCompletedEventConsumer authorizationCompletedEventConsumer(
            AuthorizationCompletedEventParser parser,
            FraudCaseCreationService creationService) {
        return new AuthorizationCompletedEventConsumer(parser, creationService);
    }

    @Bean
    KafkaAdmin.NewTopics fraudCaseTopics(
            FraudCaseConsumerProperties properties, FraudCaseProjectionProperties projection) {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(properties.dltTopic()).partitions(properties.topicPartitions()).replicas(1).build(),
                TopicBuilder.name(projection.topic()).partitions(projection.topicPartitions()).replicas(1)
                        .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT).build(),
                TopicBuilder.name(projection.topic()+".dlt").partitions(projection.topicPartitions()).replicas(1)
                        .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE).build());
    }

    @Bean
    KafkaFailurePolicy kafkaFailurePolicy() {
        return new KafkaFailurePolicy();
    }

    @Bean
    DefaultErrorHandler fraudCaseConsumerErrorHandler(
            FraudCaseConsumerProperties properties,
            KafkaTemplate<Object, Object> kafkaTemplate,
            KafkaFailurePolicy failurePolicy,
            Clock fraudCaseClock) {
        TransactIqDeadLetterPublishingRecoverer publisher =
                new TransactIqDeadLetterPublishingRecoverer(
                        kafkaTemplate,
                        (record, exception) -> new org.apache.kafka.common.TopicPartition(
                                properties.dltTopic(), record.partition()));
        publisher.setHeadersFunction(
                new RecoveryHeaders(failurePolicy, properties.groupId(), fraudCaseClock));
        publisher.setVerifyPartition(true);
        publisher.setThrowIfNoDestinationReturned(true);
        publisher.setFailIfSendResultIsError(true);
        publisher.excludeHeader(
                HeadersToAdd.EXCEPTION,
                HeadersToAdd.EX_CAUSE,
                HeadersToAdd.EX_MSG,
                HeadersToAdd.EX_STACKTRACE);

        RecoveryRetryingRecoverer recoverer = new RecoveryRetryingRecoverer(
                publisher, properties.recoveryRetryInterval(), failurePolicy);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer);
        errorHandler.setBackOffFunction(
                (record, exception) -> failurePolicy.backOff(exception, properties));
        errorHandler.setCommitRecovered(true);
        errorHandler.setAckAfterHandle(true);
        errorHandler.setResetStateOnRecoveryFailure(false);
        errorHandler.setResetStateOnExceptionChange(false);
        errorHandler.setRetryListeners(new FraudCaseRetryListener(failurePolicy));
        return errorHandler;
    }
}
