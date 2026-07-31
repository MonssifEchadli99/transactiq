package io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka;

import java.time.Duration;
import java.util.Objects;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;

public final class RecoveryRetryingRecoverer implements ConsumerAwareRecordRecoverer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RecoveryRetryingRecoverer.class);

    private final ConsumerAwareRecordRecoverer delegate;
    private final Duration retryInterval;
    private final Sleeper sleeper;
    private final KafkaFailurePolicy failurePolicy;

    public RecoveryRetryingRecoverer(
            ConsumerAwareRecordRecoverer delegate,
            Duration retryInterval,
            KafkaFailurePolicy failurePolicy) {
        this(delegate, retryInterval, failurePolicy, Thread::sleep);
    }

    RecoveryRetryingRecoverer(
            ConsumerAwareRecordRecoverer delegate,
            Duration retryInterval,
            KafkaFailurePolicy failurePolicy,
            Sleeper sleeper) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.retryInterval = Objects.requireNonNull(retryInterval, "retryInterval must not be null");
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy must not be null");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception exception) {
        String recoveryId = RecoveryHeaders.recoveryId(record);
        KafkaFailurePolicy.RecoveryCategory category = failurePolicy.recoveryCategory(exception);
        while (true) {
            try {
                delegate.accept(record, consumer, exception);
                LOGGER.info(
                        "event=kafka_dlt_published recoveryId={} category={} sourceTopic={} sourcePartition={} sourceOffset={} attempt={} exceptionClass={}",
                        recoveryId,
                        category,
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        RecoveryHeaders.deliveryAttempt(record),
                        failurePolicy.diagnosticException(exception).getClass().getName());
                return;
            } catch (RuntimeException publicationFailure) {
                LOGGER.error(
                        "event=kafka_dlt_publication_failed recoveryId={} category={} sourceTopic={} sourcePartition={} sourceOffset={} attempt={} exceptionClass={}",
                        recoveryId,
                        category,
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        RecoveryHeaders.deliveryAttempt(record),
                        publicationFailure.getClass().getName());
                sleepBeforeRetry();
            }
        }
    }

    private void sleepBeforeRetry() {
        try {
            sleeper.sleep(retryInterval.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off DLT recovery", interrupted);
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long milliseconds) throws InterruptedException;
    }
}
