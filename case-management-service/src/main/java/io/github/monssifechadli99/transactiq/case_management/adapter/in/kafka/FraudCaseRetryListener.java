package io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.RetryListener;

public final class FraudCaseRetryListener implements RetryListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(FraudCaseRetryListener.class);

    private final KafkaFailurePolicy failurePolicy;

    public FraudCaseRetryListener(KafkaFailurePolicy failurePolicy) {
        this.failurePolicy = failurePolicy;
    }

    @Override
    public void failedDelivery(
            ConsumerRecord<?, ?> record, Exception exception, int deliveryAttempt) {
        String classification = failurePolicy.isTemporaryResourceFailure(exception)
                ? "TEMPORARY_RESOURCE_FAILURE"
                : failurePolicy.recoveryCategory(exception).name();
        LOGGER.warn(
                "event=kafka_listener_failure recoveryId={} classification={} sourceTopic={} sourcePartition={} sourceOffset={} attempt={} exceptionClass={}",
                RecoveryHeaders.recoveryId(record),
                classification,
                record.topic(),
                record.partition(),
                record.offset(),
                deliveryAttempt,
                failurePolicy.diagnosticException(exception).getClass().getName());
    }

    @Override
    public void recoveryFailed(
            ConsumerRecord<?, ?> record, Exception original, Exception failure) {
        LOGGER.error(
                "event=kafka_recovery_retry recoveryId={} category={} sourceTopic={} sourcePartition={} sourceOffset={} exceptionClass={}",
                RecoveryHeaders.recoveryId(record),
                failurePolicy.recoveryCategory(original),
                record.topic(),
                record.partition(),
                record.offset(),
                failure.getClass().getName());
    }

    @Override
    public void recovered(ConsumerRecord<?, ?> record, Exception exception) {
        LOGGER.info(
                "event=kafka_listener_recovery_completed recoveryId={} category={} sourceTopic={} sourcePartition={} sourceOffset={} attempt={} exceptionClass={}",
                RecoveryHeaders.recoveryId(record),
                failurePolicy.recoveryCategory(exception),
                record.topic(),
                record.partition(),
                record.offset(),
                RecoveryHeaders.deliveryAttempt(record),
                failurePolicy.diagnosticException(exception).getClass().getName());
    }
}
