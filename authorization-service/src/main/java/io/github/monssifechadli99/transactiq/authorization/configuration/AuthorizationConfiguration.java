package io.github.monssifechadli99.transactiq.authorization.configuration;

import io.github.monssifechadli99.transactiq.authorization.adapter.out.event.AuthorizationCompletedEventMapper;
import io.github.monssifechadli99.transactiq.authorization.adapter.out.event.JdbcAuthorizationCompletedEventOutboxAdapter;
import io.github.monssifechadli99.transactiq.authorization.adapter.out.jdbc.JdbcAuthorizationLedgerAdapter;
import io.github.monssifechadli99.transactiq.authorization.adapter.out.jdbc.JdbcNonFraudCheckAdapter;
import io.github.monssifechadli99.transactiq.authorization.adapter.out.transaction.SpringTransactionExecutor;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.AuthorizationLedgerPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.AuthorizationCompletedEventOutboxPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.FraudAssessmentPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.IdempotencyClaimPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.NonFraudCheckPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.TransactionExecutorPort;
import io.github.monssifechadli99.transactiq.authorization.application.service.AuthorizationApplicationService;
import io.github.monssifechadli99.transactiq.authorization.application.service.AuthorizationCompletionService;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationPolicy;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class AuthorizationConfiguration {

    @Bean
    JdbcNonFraudCheckAdapter nonFraudCheckAdapter(JdbcClient jdbcClient) {
        return new JdbcNonFraudCheckAdapter(jdbcClient);
    }

    @Bean
    JdbcAuthorizationLedgerAdapter authorizationLedgerAdapter(JdbcClient jdbcClient) {
        return new JdbcAuthorizationLedgerAdapter(jdbcClient);
    }

    @Bean
    Clock authorizationEventClock() {
        return Clock.systemUTC();
    }

    @Bean
    AuthorizationCompletedEventMapper authorizationCompletedEventMapper(
            Clock authorizationEventClock) {
        return new AuthorizationCompletedEventMapper(
                authorizationEventClock, UUID::randomUUID);
    }

    @Bean
    JdbcAuthorizationCompletedEventOutboxAdapter authorizationCompletedEventOutboxAdapter(
            JdbcClient jdbcClient,
            AuthorizationCompletedEventMapper authorizationCompletedEventMapper) {
        return new JdbcAuthorizationCompletedEventOutboxAdapter(
                jdbcClient, authorizationCompletedEventMapper);
    }

    @Bean
    AuthorizationPolicy authorizationPolicy() {
        return new AuthorizationPolicy();
    }

    @Bean
    SpringTransactionExecutor transactionExecutor(
            PlatformTransactionManager transactionManager) {
        return new SpringTransactionExecutor(new TransactionTemplate(transactionManager));
    }

    @Bean
    AuthorizationCompletionService authorizationCompletionService(
            TransactionExecutorPort transactionExecutorPort,
            NonFraudCheckPort nonFraudCheckPort,
            AuthorizationLedgerPort authorizationLedgerPort,
            AuthorizationCompletedEventOutboxPort authorizationCompletedEventOutboxPort,
            AuthorizationPolicy authorizationPolicy) {
        return new AuthorizationCompletionService(
                transactionExecutorPort,
                nonFraudCheckPort,
                authorizationLedgerPort,
                authorizationCompletedEventOutboxPort,
                authorizationPolicy);
    }

    @Bean
    AuthorizationApplicationService authorizationApplicationService(
            IdempotencyClaimPort idempotencyClaimPort,
            FraudAssessmentPort fraudAssessmentPort,
            AuthorizationCompletionService authorizationCompletionService) {
        return new AuthorizationApplicationService(
                idempotencyClaimPort,
                fraudAssessmentPort,
                authorizationCompletionService);
    }
}
