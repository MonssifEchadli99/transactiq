package io.github.monssifechadli99.transactiq.case_management.projection;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;

public final class KafkaProjectionTransactionalProducerFactory implements ProjectionTransactionalProducerFactory {
    private final Map<String, Object> producerProperties;
    private final Duration operationTimeout;

    public KafkaProjectionTransactionalProducerFactory(Map<String, Object> producerProperties, Duration operationTimeout) {
        this.producerProperties = Map.copyOf(producerProperties);
        this.operationTimeout = operationTimeout;
    }

    @Override
    public ProjectionTransactionalProducer create(String topic, int partition, String transactionalId) {
        Map<String, Object> configuration = new HashMap<>(producerProperties);
        configuration.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, transactionalId);
        configuration.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        configuration.put(ProducerConfig.ACKS_CONFIG, "all");
        configuration.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        configuration.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(configuration);
        return new ProjectionTransactionalProducer() {
            public void initTransactions() { producer.initTransactions(); }
            public void beginTransaction() { producer.beginTransaction(); }
            public void send(int targetPartition, byte[] key, byte[] value) throws Exception {
                producer.send(new ProducerRecord<>(topic, targetPartition, key, value))
                        .get(operationTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            public void commitTransaction() { producer.commitTransaction(); }
            public void abortTransaction() { producer.abortTransaction(); }
            public void close() { producer.close(Duration.ZERO); }
        };
    }
}
