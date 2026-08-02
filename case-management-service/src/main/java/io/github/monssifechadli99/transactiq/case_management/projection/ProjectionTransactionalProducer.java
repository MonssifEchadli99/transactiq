package io.github.monssifechadli99.transactiq.case_management.projection;

public interface ProjectionTransactionalProducer extends AutoCloseable {
    void initTransactions();
    void beginTransaction();
    void send(int partition, byte[] key, byte[] value) throws Exception;
    void commitTransaction();
    void abortTransaction();
    @Override void close();
}
