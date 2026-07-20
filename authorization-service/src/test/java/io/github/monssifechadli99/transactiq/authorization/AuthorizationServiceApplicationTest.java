package io.github.monssifechadli99.transactiq.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.monssifechadli99.transactiq.authorization.adapter.out.jdbc.JdbcAuthorizationLedgerAdapter;
import io.github.monssifechadli99.transactiq.authorization.adapter.out.jdbc.JdbcIdempotencyClaimAdapter;
import io.github.monssifechadli99.transactiq.authorization.adapter.out.jdbc.JdbcNonFraudCheckAdapter;
import io.github.monssifechadli99.transactiq.authorization.adapter.out.memory.DeterministicFraudAssessmentAdapter;
import io.github.monssifechadli99.transactiq.authorization.adapter.out.memory.InMemoryAuthorizationLedgerAdapter;
import io.github.monssifechadli99.transactiq.authorization.adapter.out.transaction.SpringTransactionExecutor;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizeTransactionUseCase;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.AuthorizationLedgerPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.FraudAssessmentPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.IdempotencyClaimPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.NonFraudCheckPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.TransactionExecutorPort;
import io.github.monssifechadli99.transactiq.authorization.support.AuthorizationServiceIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

@AuthorizationServiceIntegrationTest
class AuthorizationServiceApplicationTest {

    @Autowired
    private AuthorizeTransactionUseCase authorizeTransactionUseCase;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextProvidesAuthorizationUseCase() {
        assertNotNull(authorizeTransactionUseCase);
    }

    @Test
    void productionContextProvidesExactlyOneBeanForEachRuntimePort() {
        assertSingleBean(FraudAssessmentPort.class, DeterministicFraudAssessmentAdapter.class);
        assertSingleBean(NonFraudCheckPort.class, JdbcNonFraudCheckAdapter.class);
        assertSingleBean(AuthorizationLedgerPort.class, JdbcAuthorizationLedgerAdapter.class);
        assertSingleBean(IdempotencyClaimPort.class, JdbcIdempotencyClaimAdapter.class);
        assertSingleBean(TransactionExecutorPort.class, SpringTransactionExecutor.class);
        assertEquals(0, applicationContext.getBeansOfType(InMemoryAuthorizationLedgerAdapter.class)
                .size());
    }

    private <T> void assertSingleBean(Class<T> portType, Class<? extends T> implementationType) {
        var beans = applicationContext.getBeansOfType(portType);
        assertEquals(1, beans.size());
        assertInstanceOf(implementationType, beans.values().iterator().next());
    }
}
