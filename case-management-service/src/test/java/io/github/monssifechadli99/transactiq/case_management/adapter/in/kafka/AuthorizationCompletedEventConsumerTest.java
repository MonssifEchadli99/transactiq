package io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka;

import static io.github.monssifechadli99.transactiq.case_management.support.AuthorizationEventFixtures.clearEvent;
import static io.github.monssifechadli99.transactiq.case_management.support.AuthorizationEventFixtures.reviewEvent;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.monssifechadli99.transactiq.case_management.application.port.out.FraudCaseStore;
import io.github.monssifechadli99.transactiq.case_management.application.service.FraudCaseCreationService;
import io.github.monssifechadli99.transactiq.observability.PortfolioMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

class AuthorizationCompletedEventConsumerTest {

    private final FraudCaseStore store = mock(FraudCaseStore.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AuthorizationCompletedEventConsumer consumer =
            new AuthorizationCompletedEventConsumer(
                    new AuthorizationCompletedEventParser(),
                    new FraudCaseCreationService(store),
                    new PortfolioMetrics(meterRegistry));
    private final Acknowledgment acknowledgment = mock(Acknowledgment.class);

    @Test
    void validNonCaseEventIsAcknowledgedWithoutPersistence() {
        byte[] value = clearEvent(UUID.randomUUID(), UUID.randomUUID())
                .build()
                .toByteArray();

        consumer.consume(record(value), acknowledgment);

        verify(store, never()).create(org.mockito.ArgumentMatchers.any());
        verify(acknowledgment).acknowledge();
        org.assertj.core.api.Assertions.assertThat(meterRegistry.get("transactiq.case.event.processed")
                        .tag("result", "not_required")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void databaseFailureLeavesRecordUnacknowledgedForRetry() {
        byte[] value = reviewEvent(UUID.randomUUID(), UUID.randomUUID())
                .build()
                .toByteArray();
        when(store.create(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("synthetic database failure"));

        assertThrows(
                IllegalStateException.class,
                () -> consumer.consume(record(value), acknowledgment));

        verify(acknowledgment, never()).acknowledge();
        org.assertj.core.api.Assertions.assertThat(meterRegistry.get("transactiq.case.event.processed")
                        .tag("result", "failed")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void invalidPayloadLeavesRecordUnacknowledged() {
        assertThrows(
                RuntimeException.class,
                () -> consumer.consume(record(new byte[] {1, 2, 3}), acknowledgment));

        verify(acknowledgment, never()).acknowledge();
    }

    private static ConsumerRecord<byte[], byte[]> record(byte[] value) {
        return new ConsumerRecord<>(
                "transactiq.authorization.completed.v1", 0, 0, "key".getBytes(), value);
    }
}
