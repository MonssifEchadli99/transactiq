package io.github.monssifechadli99.transactiq.authorization.configuration;

import io.github.monssifechadli99.transactiq.authorization.adapter.out.jdbc.JdbcAuthorizationLedgerAdapter;
import io.github.monssifechadli99.transactiq.authorization.adapter.out.jdbc.JdbcNonFraudCheckAdapter;
import io.github.monssifechadli99.transactiq.authorization.adapter.out.memory.DeterministicFraudAssessmentAdapter;
import io.github.monssifechadli99.transactiq.authorization.adapter.out.transaction.SpringTransactionExecutor;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.AuthorizationLedgerPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.FraudAssessmentPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.IdempotencyClaimPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.NonFraudCheckPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.TransactionExecutorPort;
import io.github.monssifechadli99.transactiq.authorization.application.service.AuthorizationApplicationService;
import io.github.monssifechadli99.transactiq.authorization.application.service.AuthorizationCompletionService;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class AuthorizationConfiguration {

    @Bean
    DeterministicFraudAssessmentAdapter fraudAssessmentAdapter() {
        return new DeterministicFraudAssessmentAdapter();
    }

    @Bean
    JdbcNonFraudCheckAdapter nonFraudCheckAdapter(JdbcClient jdbcClient) {
        return new JdbcNonFraudCheckAdapter(jdbcClient);
    }

    @Bean
    JdbcAuthorizationLedgerAdapter authorizationLedgerAdapter(JdbcClient jdbcClient) {
        return new JdbcAuthorizationLedgerAdapter(jdbcClient);
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
            AuthorizationPolicy authorizationPolicy) {
        return new AuthorizationCompletionService(
                transactionExecutorPort,
                nonFraudCheckPort,
                authorizationLedgerPort,
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
