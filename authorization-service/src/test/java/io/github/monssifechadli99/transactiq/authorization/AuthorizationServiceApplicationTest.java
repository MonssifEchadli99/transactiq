package io.github.monssifechadli99.transactiq.authorization;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizeTransactionUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AuthorizationServiceApplicationTest {

    @Autowired
    private AuthorizeTransactionUseCase authorizeTransactionUseCase;

    @Test
    void contextProvidesAuthorizationUseCase() {
        assertNotNull(authorizeTransactionUseCase);
    }
}
