package io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

public final class TransactIqDeadLetterPublishingRecoverer
        extends DeadLetterPublishingRecoverer {

    public TransactIqDeadLetterPublishingRecoverer(
            KafkaOperations<?, ?> kafkaOperations,
            BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destinationResolver) {
        super(kafkaOperations, destinationResolver);
    }

    @Override
    protected ProducerRecord<Object, Object> createProducerRecord(
            ConsumerRecord<?, ?> record,
            TopicPartition topicPartition,
            Headers headers,
            byte[] key,
            byte[] value) {
        if (topicPartition.partition() != record.partition()) {
            throw new IllegalStateException(
                    "DLT destination partition must match the source partition");
        }
        Map<String, byte[]> authoritative = new LinkedHashMap<>();
        for (String name : RecoveryHeaders.NAMES) {
            Header latest = headers.lastHeader(name);
            if (latest != null) {
                authoritative.put(name, latest.value());
            }
            headers.remove(name);
        }
        authoritative.forEach(headers::add);
        return super.createProducerRecord(record, topicPartition, headers, key, value);
    }
}
