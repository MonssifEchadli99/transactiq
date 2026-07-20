package io.github.monssifechadli99.transactiq.authorization.adapter.out.transaction;

import io.github.monssifechadli99.transactiq.authorization.application.port.out.TransactionExecutorPort;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

public final class SpringTransactionExecutor implements TransactionExecutorPort {

    private final TransactionTemplate transactionTemplate;

    public SpringTransactionExecutor(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = Objects.requireNonNull(
                transactionTemplate, "transactionTemplate must not be null");
    }

    @Override
    public <T> T execute(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation must not be null");
        return transactionTemplate.execute(status -> operation.get());
    }
}
