package io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;

class RecoveryRetryingRecovererTest {

    @Test
    void failedPublicationBacksOffAndRetriesRecoveryWithoutReturningToProcessing() {
        RuntimeException failure = new RuntimeException("broker unavailable");
        AtomicLong publications = new AtomicLong();
        ConsumerAwareRecordRecoverer delegate = (record, consumer, original) -> {
            if (publications.incrementAndGet() == 1) {
                throw failure;
            }
        };
        AtomicLong slept = new AtomicLong();
        RecoveryRetryingRecoverer recoverer = new RecoveryRetryingRecoverer(
                delegate, Duration.ofSeconds(1), new KafkaFailurePolicy(), slept::set);
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(
                "source", 0, 9, new byte[0], new byte[] {1});
        Consumer<?, ?> consumer = mock(Consumer.class);

        recoverer.accept(record, consumer, new IllegalStateException("processing"));

        assertEquals(2, publications.get());
        assertEquals(1_000, slept.get());
    }

    @Test
    void successfulPublicationReturnsNormally() {
        ConsumerAwareRecordRecoverer delegate = mock(ConsumerAwareRecordRecoverer.class);
        RecoveryRetryingRecoverer recoverer = new RecoveryRetryingRecoverer(
                delegate, Duration.ofSeconds(1), new KafkaFailurePolicy(), ignored -> {});
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(
                "source", 0, 9, new byte[0], new byte[] {1});
        Consumer<?, ?> consumer = mock(Consumer.class);
        Exception original = new IllegalStateException("processing");

        recoverer.accept(record, consumer, original);

        verify(delegate).accept(record, consumer, original);
    }
}
