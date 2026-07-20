package io.github.monssifechadli99.transactiq.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.authorization.support.AuthorizationServiceIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@AuthorizationServiceIntegrationTest
class DatabaseSmokeIntegrationTest {

    @Autowired
    private PostgreSQLContainer postgresqlContainer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void postgresqlStartsAndSpringConnectsAfterLiquibaseRuns() {
        assertTrue(postgresqlContainer.isRunning());
        assertEquals(
                postgresqlContainer.getDatabaseName(),
                jdbcTemplate.queryForObject("SELECT current_database()", String.class));
        assertEquals(
                Boolean.TRUE,
                jdbcTemplate.queryForObject(
                        """
                        SELECT EXISTS (
                            SELECT 1
                            FROM information_schema.tables
                            WHERE table_schema = 'public'
                              AND table_name = 'databasechangeloglock'
                        )
                        """,
                        Boolean.class));
    }
}
