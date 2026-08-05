package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.kafka;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;

/** Test-only template that fails one real DLT send future, then gates the successful redelivery. */
final class FailingDltKafkaTemplate extends KafkaTemplate<Object, Object> {

    private static final String DLT_TOPIC = "investigation-projection-kafka-it.dlt";

    private final AtomicReference<FailurePlan> failurePlan = new AtomicReference<>();

    FailingDltKafkaTemplate(ProducerFactory<Object, Object> producerFactory) {
        super(producerFactory);
    }

    void arm(byte[] key) {
        failurePlan.set(new FailurePlan(key.clone()));
    }

    boolean awaitFailedFuture(Duration timeout) {
        return await(requiredPlan().failedFutureReturned, timeout);
    }

    boolean awaitRedeliveryPublish(Duration timeout) {
        return await(requiredPlan().redeliveryPublishEntered, timeout);
    }

    void releaseSuccessfulPublish() {
        FailurePlan plan = failurePlan.get();
        if (plan != null) {
            plan.releaseSuccessfulPublish.countDown();
        }
    }

    int targetedSendAttempts() {
        return requiredPlan().targetedSendAttempts.get();
    }

    void disarm() {
        releaseSuccessfulPublish();
        failurePlan.set(null);
    }

    @Override
    public CompletableFuture<SendResult<Object, Object>> send(ProducerRecord<Object, Object> record) {
        FailurePlan plan = failurePlan.get();
        if (plan == null || !DLT_TOPIC.equals(record.topic()) || !(record.key() instanceof byte[] actualKey)
                || !Arrays.equals(plan.expectedKey, actualKey)) {
            return super.send(record);
        }

        int attempt = plan.targetedSendAttempts.incrementAndGet();
        if (attempt == 1) {
            plan.failedFutureReturned.countDown();
            return CompletableFuture.failedFuture(
                    new KafkaException("Deterministic test DLT publication failure"));
        }

        plan.redeliveryPublishEntered.countDown();
        try {
            if (!plan.releaseSuccessfulPublish.await(15, TimeUnit.SECONDS)) {
                return CompletableFuture.failedFuture(
                        new KafkaException("Timed out waiting to release the test DLT publication"));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(
                    new KafkaException("Interrupted while waiting to release the test DLT publication"));
        }
        return super.send(record);
    }

    private FailurePlan requiredPlan() {
        FailurePlan plan = failurePlan.get();
        if (plan == null) {
            throw new IllegalStateException("No deterministic DLT failure plan is armed");
        }
        return plan;
    }

    private static boolean await(CountDownLatch latch, Duration timeout) {
        try {
            return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static final class FailurePlan {
        private final byte[] expectedKey;
        private final AtomicInteger targetedSendAttempts = new AtomicInteger();
        private final CountDownLatch failedFutureReturned = new CountDownLatch(1);
        private final CountDownLatch redeliveryPublishEntered = new CountDownLatch(1);
        private final CountDownLatch releaseSuccessfulPublish = new CountDownLatch(1);

        private FailurePlan(byte[] expectedKey) {
            this.expectedKey = expectedKey;
        }
    }
}
