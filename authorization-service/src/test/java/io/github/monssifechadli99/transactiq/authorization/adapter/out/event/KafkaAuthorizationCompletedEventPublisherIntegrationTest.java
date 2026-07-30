package io.github.monssifechadli99.transactiq.authorization.adapter.out.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Timestamp;
import io.github.monssifechadli99.transactiq.authorization.application.model.ClaimedAuthorizationOutboxEvent;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.AuthorizationOutboxStorePort;
import io.github.monssifechadli99.transactiq.authorization.application.service.AuthorizationOutboxRelay;
import io.github.monssifechadli99.transactiq.authorization.configuration.AuthorizationOutboxPublisherProperties;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.AuthorizationCompletedEvent;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventAuthorizationDecision;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventChannel;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventDeclineReason;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventFraudAssessment;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventFraudRuleMatch;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventFraudRuleSeverity;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventNonFraudResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
class KafkaAuthorizationCompletedEventPublisherIntegrationTest {

    private static final String KAFKA_IMAGE = "apache/kafka:4.1.2";
    private static final String TOPIC = "transactiq.authorization.completed.v1";
    private static final String PARTITION_KEY =
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    private static final Instant NOW = Instant.parse("2026-07-22T14:00:00Z");

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE);

    @Test
    void publishesValidClearReviewAndHighRiskEventsAndRecoversPendingEventAfterConnectivityFailure()
            throws Exception {
        createTopic();
        KafkaTemplate<Object, Object> liveTemplate = kafkaTemplate(KAFKA.getBootstrapServers(), 10_000L);
        KafkaTemplate<Object, Object> unavailableTemplate = kafkaTemplate("127.0.0.1:1", 250L);
        try (KafkaConsumer<String, byte[]> consumer = consumer()) {
            TopicPartition partition = new TopicPartition(TOPIC, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));

            KafkaAuthorizationCompletedEventPublisher publisher =
                    new KafkaAuthorizationCompletedEventPublisher(liveTemplate, TOPIC);
            List<AuthorizationCompletedEvent> expected = List.of(
                    event("90000000-0000-4000-8000-000000000001", EventFraudAssessment.EVENT_FRAUD_ASSESSMENT_CLEAR, 0),
                    event("90000000-0000-4000-8000-000000000002", EventFraudAssessment.EVENT_FRAUD_ASSESSMENT_REVIEW, 25),
                    event("90000000-0000-4000-8000-000000000003", EventFraudAssessment.EVENT_FRAUD_ASSESSMENT_HIGH_RISK, 80));
            for (AuthorizationCompletedEvent event : expected) {
                publisher.publish(claimed(event, 0));
            }

            List<ConsumerRecord<String, byte[]>> consumed = consume(consumer, 3);
            assertEquals(List.of(PARTITION_KEY, PARTITION_KEY, PARTITION_KEY),
                    consumed.stream().map(ConsumerRecord::key).toList());
            assertTrue(consumed.stream().allMatch(record -> !record.headers().iterator().hasNext()));
            List<AuthorizationCompletedEvent> parsed = consumed.stream()
                    .map(record -> parse(record.value()))
                    .toList();
            assertEquals(expected, parsed);
            assertEquals(List.of(0, 25, 80),
                    parsed.stream().map(AuthorizationCompletedEvent::getRiskScore).toList());
            assertEquals(List.of(false, true, true),
                    parsed.stream().map(AuthorizationCompletedEvent::getCaseRequired).toList());

            AuthorizationCompletedEvent retryEvent = event(
                    "90000000-0000-4000-8000-000000000004",
                    EventFraudAssessment.EVENT_FRAUD_ASSESSMENT_REVIEW,
                    25);
            StatefulPendingStore pending = new StatefulPendingStore(claimed(retryEvent, 0), NOW);
            AuthorizationOutboxPublisherProperties properties = properties();

            new AuthorizationOutboxRelay(
                            pending,
                            new KafkaAuthorizationCompletedEventPublisher(unavailableTemplate, TOPIC),
                            properties,
                            Clock.fixed(NOW, ZoneOffset.UTC))
                    .publishDue();

            assertEquals("PENDING", pending.state);
            assertEquals(1, pending.attemptCount);
            assertEquals(NOW.plusSeconds(1), pending.nextAttemptAt);
            assertEquals(AuthorizationOutboxRelay.PUBLISH_FAILURE_CODE, pending.errorCode);

            new AuthorizationOutboxRelay(
                            pending,
                            publisher,
                            properties,
                            Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC))
                    .publishDue();

            assertEquals("PUBLISHED", pending.state);
            assertEquals(retryEvent, parse(consume(consumer, 1).getFirst().value()));
        } finally {
            unavailableTemplate.destroy();
            liveTemplate.destroy();
        }
    }

    private static void createTopic() throws Exception {
        Map<String, Object> properties = Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (Admin admin = Admin.create(properties)) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1)))
                    .all()
                    .get(10, TimeUnit.SECONDS);
        }
    }

    private static KafkaTemplate<Object, Object> kafkaTemplate(
            String bootstrapServers, long maxBlockMillis) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlockMillis);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 1_000);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 500);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(properties));
    }

    private static KafkaConsumer<String, byte[]> consumer() {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "authorization-outbox-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new KafkaConsumer<>(properties);
    }

    private static List<ConsumerRecord<String, byte[]>> consume(
            KafkaConsumer<String, byte[]> consumer, int expectedCount) {
        List<ConsumerRecord<String, byte[]>> consumed = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (consumed.size() < expectedCount && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofSeconds(1)).forEach(consumed::add);
        }
        assertEquals(expectedCount, consumed.size());
        return consumed;
    }

    private static AuthorizationCompletedEvent parse(byte[] payload) {
        try {
            return AuthorizationCompletedEvent.parseFrom(payload);
        } catch (Exception invalidEvent) {
            throw new AssertionError("Kafka value was not a valid authorization-completed v1 event", invalidEvent);
        }
    }

    private static ClaimedAuthorizationOutboxEvent claimed(
            AuthorizationCompletedEvent event, int attemptCount) {
        return new ClaimedAuthorizationOutboxEvent(
                UUID.fromString(event.getEventId()),
                UUID.randomUUID(),
                PARTITION_KEY,
                event.toByteArray(),
                attemptCount);
    }

    private static AuthorizationCompletedEvent event(
            String eventId, EventFraudAssessment assessment, int riskScore) {
        AuthorizationCompletedEvent.Builder event = AuthorizationCompletedEvent.newBuilder()
                .setEventId(eventId)
                .setOccurredAt(timestamp(NOW))
                .setRequestId(eventId.replaceFirst("^9", "8"))
                .setCardTokenFingerprint(PARTITION_KEY)
                .setMerchantId("merchant_synthetic_kafka")
                .setMerchantCategoryCode("5411")
                .setAmount("42.5")
                .setCurrency("EUR")
                .setCountry("DE")
                .setChannel(EventChannel.EVENT_CHANNEL_ECOMMERCE)
                .setTransactionTime(timestamp(NOW.minusSeconds(5)))
                .setNonFraudResult(EventNonFraudResult.EVENT_NON_FRAUD_RESULT_PASSED)
                .setFraudAssessment(assessment)
                .setRiskScore(riskScore)
                .setCaseRequired(assessment != EventFraudAssessment.EVENT_FRAUD_ASSESSMENT_CLEAR);
        if (assessment == EventFraudAssessment.EVENT_FRAUD_ASSESSMENT_CLEAR) {
            return event.setDecision(EventAuthorizationDecision.EVENT_AUTHORIZATION_DECISION_APPROVED)
                    .build();
        }
        EventFraudRuleSeverity severity =
                assessment == EventFraudAssessment.EVENT_FRAUD_ASSESSMENT_HIGH_RISK
                        ? EventFraudRuleSeverity.EVENT_FRAUD_RULE_SEVERITY_HIGH_RISK
                        : EventFraudRuleSeverity.EVENT_FRAUD_RULE_SEVERITY_REVIEW;
        event.addMatchedRules(EventFraudRuleMatch.newBuilder()
                .setRuleCode("SYNTHETIC_KAFKA_RULE")
                .setSeverity(severity)
                .setEvidence("synthetic Kafka integration evidence")
                .setScoreContribution(riskScore));
        if (assessment == EventFraudAssessment.EVENT_FRAUD_ASSESSMENT_HIGH_RISK) {
            event.setDecision(EventAuthorizationDecision.EVENT_AUTHORIZATION_DECISION_DECLINED)
                    .setDeclineReason(EventDeclineReason.EVENT_DECLINE_REASON_HIGH_FRAUD_RISK);
        } else {
            event.setDecision(EventAuthorizationDecision.EVENT_AUTHORIZATION_DECISION_APPROVED);
        }
        return event.build();
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static AuthorizationOutboxPublisherProperties properties() {
        return new AuthorizationOutboxPublisherProperties(
                true,
                TOPIC,
                10,
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofSeconds(10));
    }

    private static final class StatefulPendingStore implements AuthorizationOutboxStorePort {

        private final ClaimedAuthorizationOutboxEvent original;
        private String state = "PENDING";
        private int attemptCount;
        private Instant nextAttemptAt;
        private UUID activeLease;
        private String errorCode;

        private StatefulPendingStore(
                ClaimedAuthorizationOutboxEvent original, Instant nextAttemptAt) {
            this.original = original;
            this.nextAttemptAt = nextAttemptAt;
        }

        @Override
        public List<ClaimedAuthorizationOutboxEvent> claimDue(
                int batchSize, Instant now, Duration leaseDuration) {
            if (!state.equals("PENDING") || now.isBefore(nextAttemptAt)) {
                return List.of();
            }
            state = "IN_FLIGHT";
            activeLease = UUID.randomUUID();
            return List.of(new ClaimedAuthorizationOutboxEvent(
                    original.eventId(),
                    activeLease,
                    original.partitionKey(),
                    original.payload(),
                    attemptCount));
        }

        @Override
        public boolean markPublished(UUID eventId, UUID leaseToken, Instant publishedAt) {
            if (!leaseToken.equals(activeLease)) {
                return false;
            }
            state = "PUBLISHED";
            activeLease = null;
            errorCode = null;
            return true;
        }

        @Override
        public boolean markFailed(
                UUID eventId,
                UUID leaseToken,
                Instant nextAttemptAt,
                String errorCode) {
            if (!leaseToken.equals(activeLease)) {
                return false;
            }
            state = "PENDING";
            attemptCount++;
            this.nextAttemptAt = nextAttemptAt;
            this.errorCode = errorCode;
            activeLease = null;
            return true;
        }
    }
}
