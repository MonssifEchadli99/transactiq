package io.github.monssifechadli99.transactiq.authorization.application.port.out;

import java.util.function.Supplier;

public interface TransactionExecutorPort {

    <T> T execute(Supplier<T> operation);
}
