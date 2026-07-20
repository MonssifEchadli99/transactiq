package io.github.monssifechadli99.transactiq.authorization.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class PostgreSqlTestcontainersConfiguration {

    private static final DockerImageName POSTGRESQL_IMAGE =
            DockerImageName.parse("postgres:18.4-alpine3.24");

    private static final PostgreSQLContainer POSTGRESQL_CONTAINER =
            new PostgreSQLContainer(POSTGRESQL_IMAGE)
                    .withDatabaseName("transactiq_authorization_test")
                    .withUsername("transactiq_test")
                    .withPassword("transactiq_test");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresqlContainer() {
        return POSTGRESQL_CONTAINER;
    }
}
