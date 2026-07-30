package io.github.monssifechadli99.transactiq.case_management.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class PostgreSqlTestcontainersConfiguration {

    private static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18.4-alpine3.24"))
                    .withDatabaseName("transactiq_case_management_test")
                    .withUsername("transactiq_case_test")
                    .withPassword("transactiq_case_test");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresqlContainer() {
        return POSTGRESQL;
    }
}
