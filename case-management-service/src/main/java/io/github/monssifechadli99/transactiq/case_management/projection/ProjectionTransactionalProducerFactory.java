package io.github.monssifechadli99.transactiq.case_management.projection;

public interface ProjectionTransactionalProducerFactory {
    ProjectionTransactionalProducer create(String topic, int partition, String transactionalId);
}
