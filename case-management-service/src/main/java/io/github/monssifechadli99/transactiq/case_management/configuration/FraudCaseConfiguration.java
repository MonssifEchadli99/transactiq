package io.github.monssifechadli99.transactiq.case_management.configuration;

import io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka.AuthorizationCompletedEventConsumer;
import io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka.AuthorizationCompletedEventParser;
import io.github.monssifechadli99.transactiq.case_management.adapter.out.jdbc.JdbcFraudCaseStore;
import io.github.monssifechadli99.transactiq.case_management.application.port.out.FraudCaseStore;
import io.github.monssifechadli99.transactiq.case_management.application.service.FraudCaseCreationService;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableConfigurationProperties(FraudCaseConsumerProperties.class)
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
            Clock fraudCaseClock) {
        return new JdbcFraudCaseStore(
                jdbcClient,
                new TransactionTemplate(transactionManager),
                fraudCaseClock,
                UUID::randomUUID);
    }

    @Bean
    FraudCaseCreationService fraudCaseCreationService(FraudCaseStore fraudCaseStore) {
        return new FraudCaseCreationService(fraudCaseStore);
    }

    @Bean
    AuthorizationCompletedEventConsumer authorizationCompletedEventConsumer(
            AuthorizationCompletedEventParser parser,
            FraudCaseCreationService creationService) {
        return new AuthorizationCompletedEventConsumer(parser, creationService);
    }

    @Bean
    DefaultErrorHandler fraudCaseConsumerErrorHandler(FraudCaseConsumerProperties properties) {
        return new DefaultErrorHandler(new FixedBackOff(
                properties.retryInterval().toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
