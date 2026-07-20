package io.github.monssifechadli99.transactiq.authorization.configuration;

import io.github.monssifechadli99.transactiq.authorization.adapter.out.jdbc.JdbcIdempotencyClaimAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class IdempotencyConfiguration {

    @Bean
    JdbcIdempotencyClaimAdapter idempotencyClaimAdapter(
            JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
        return new JdbcIdempotencyClaimAdapter(
                jdbcClient, new TransactionTemplate(transactionManager));
    }
}
