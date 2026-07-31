package io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka;

import static io.github.monssifechadli99.transactiq.case_management.support.AuthorizationEventFixtures.reviewEvent;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.case_management.adapter.out.jdbc.JdbcFraudCaseStore;
import io.github.monssifechadli99.transactiq.case_management.application.port.out.FraudCaseStore;
import io.github.monssifechadli99.transactiq.case_management.configuration.FraudCaseConsumerProperties;
import io.github.monssifechadli99.transactiq.case_management.domain.AuthorizationEventSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@TestMethodOrder(OrderAnnotation.class)
@SpringBootTest(
        classes = KafkaRecoveryFailureModesIntegrationTest.FaultInjectionConfiguration.class,
        properties = {
            "fraud-case.consumer.topic=case-recovery-failure-source",
            "fraud-case.consumer.dlt-topic=case-recovery-failure-source.dlt",
            "fraud-case.consumer.group-id=case-recovery-failure-group",
            "fraud-case.consumer.topic-partitions=1",
            "fraud-case.consumer.retry-initial-interval=10ms",
            "fraud-case.consumer.retry-maximum-interval=30ms",
            "fraud-case.consumer.recovery-retry-interval=50ms",
            "spring.kafka.producer.properties.delivery.timeout.ms=1000",
            "spring.kafka.producer.properties.request.timeout.ms=500",
            "spring.kafka.producer.properties.retry.backoff.ms=50"
        })
class KafkaRecoveryFailureModesIntegrationTest {

    private static final String SOURCE = "case-recovery-failure-source";
    private static final String DLT = SOURCE + ".dlt";
    private static final String GROUP = "case-recovery-failure-group";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("apache/kafka-native:4.1.0"));

    @Container
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18.4-alpine3.24"))
            .withDatabaseName("transactiq_case_management")
            .withUsername("case_test")
            .withPassword("case_test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
    }

    @Autowired KafkaTemplate<byte[], byte[]> kafkaTemplate;
    @Autowired KafkaListenerEndpointRegistry listenerRegistry;
    @Autowired ControlledFraudCaseStore controlledStore;
    @Autowired KafkaFailurePolicy failurePolicy;
    @Autowired FraudCaseConsumerProperties consumerProperties;
    @Autowired Clock clock;

    @BeforeAll
    static void createTwoPartitionSource() throws Exception {
        try (AdminClient admin = admin()) {
            if (!admin.listTopics().names().get(10, TimeUnit.SECONDS).contains(SOURCE)) {
                admin.createTopics(List.of(new NewTopic(SOURCE, 2, (short) 1)))
                        .all().get(10, TimeUnit.SECONDS);
            } else {
                admin.createPartitions(Map.of(SOURCE, NewPartitions.increaseTo(2)))
                        .all().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @Order(1)
    void missingDltPartitionBlocksOffsetWithoutFallbackUntilPartitionIsAdded() throws Exception {
        byte[] poison = new byte[] {1, 2, 3};
        kafkaTemplate.send(new ProducerRecord<>(SOURCE, 1, new byte[] {9}, poison))
                .get(10, TimeUnit.SECONDS);

        await(() -> committedOffset(1) < 1, Duration.ofSeconds(2));
        Thread.sleep(300);
        assertEquals(-1, committedOffset(1));
        assertFalse(recoveryRecords(SOURCE + ":1:0").stream().findAny().isPresent());
        assertTrue(readDlt().stream().noneMatch(record -> record.partition() == 0
                && SOURCE.concat(":1:0").equals(header(record, RecoveryHeaders.RECOVERY_ID))));

        try (AdminClient admin = admin()) {
            admin.createPartitions(Map.of(DLT, NewPartitions.increaseTo(2)))
                    .all().get(10, TimeUnit.SECONDS);
        }

        await(() -> recoveryRecords(SOURCE + ":1:0").size() == 1, Duration.ofSeconds(15));
        ConsumerRecord<byte[], byte[]> recovered = recoveryRecords(SOURCE + ":1:0").getFirst();
        assertEquals(1, recovered.partition());
        assertArrayEquals(poison, recovered.value());
        await(() -> committedOffset(1) == 1, Duration.ofSeconds(10));
    }

    @Test
    @Order(2)
    void brokerRejectedDltRetriesRecoveryWithoutRestartingFiveProcessingAttempts() throws Exception {
        UUID eventId = UUID.randomUUID();
        controlledStore.failUnexpectedlyFor(eventId);
        setDltMaximumMessageBytes(100);
        byte[] poison = reviewEvent(eventId, UUID.randomUUID()).build().toByteArray();
        kafkaTemplate.send(new ProducerRecord<>(SOURCE, 0, eventId.toString()
                .getBytes(StandardCharsets.UTF_8), poison)).get(10, TimeUnit.SECONDS);

        await(() -> controlledStore.attempts() == 5, Duration.ofSeconds(10));
        Thread.sleep(2_300);
        assertEquals(5, controlledStore.attempts());
        assertEquals(-1, committedOffset(0));
        assertTrue(recoveryRecords(SOURCE + ":0:0").isEmpty());

        setDltMaximumMessageBytes(1_048_588);
        await(() -> recoveryRecords(SOURCE + ":0:0").size() == 1, Duration.ofSeconds(15));
        await(() -> committedOffset(0) == 1, Duration.ofSeconds(10));
        assertEquals(5, controlledStore.attempts());

        controlledStore.clearFailure();
        kafkaTemplate.send(SOURCE, reviewEvent(UUID.randomUUID(), UUID.randomUUID())
                .build().toByteArray()).get(10, TimeUnit.SECONDS);
        await(() -> controlledStore.successfulCreates() == 1, Duration.ofSeconds(10));
        await(() -> committedOffset(0) == 2, Duration.ofSeconds(10));
    }

    @Test
    @Order(3)
    void duplicateRealDltPublicationsRetainTheSameStableRecoveryIdentityAndBytes() {
        byte[] key = new byte[] {4, 5};
        byte[] value = new byte[] {6, 7, 8};
        ConsumerRecord<byte[], byte[]> source =
                new ConsumerRecord<>(SOURCE, 0, 777, key, value);
        TransactIqDeadLetterPublishingRecoverer publisher = publisher();
        Exception failure = new IllegalStateException("synthetic processing failure");

        publisher.accept(source, null, failure);
        publisher.accept(source, null, failure);

        List<ConsumerRecord<byte[], byte[]>> duplicates = recoveryRecords(SOURCE + ":0:777");
        assertEquals(2, duplicates.size());
        duplicates.forEach(record -> {
            assertEquals(0, record.partition());
            assertArrayEquals(key, record.key());
            assertArrayEquals(value, record.value());
            assertEquals(SOURCE + ":0:777", header(record, RecoveryHeaders.RECOVERY_ID));
            assertEquals(SOURCE, originalTopic(record));
            assertEquals(0, originalPartition(record));
            assertEquals(777L, originalOffset(record));
        });
    }

    @Test
    @Order(4)
    void restartUsesCommittedRecoveredOffsetAndDoesNotQuarantinePoisonAgain() throws Exception {
        kafkaTemplate.send(new ProducerRecord<>(SOURCE, 0, new byte[] {1}, new byte[0]))
                .get(10, TimeUnit.SECONDS);
        await(() -> recoveryRecords(SOURCE + ":0:2").size() == 1, Duration.ofSeconds(10));
        await(() -> committedOffset(0) == 3, Duration.ofSeconds(10));

        var container = listenerRegistry.getListenerContainers().iterator().next();
        CountDownLatch stopped = new CountDownLatch(1);
        container.stop(stopped::countDown);
        assertTrue(stopped.await(10, TimeUnit.SECONDS));
        container.start();
        Thread.sleep(1_000);
        assertEquals(1, recoveryRecords(SOURCE + ":0:2").size());

        int successfulBefore = controlledStore.successfulCreates();
        kafkaTemplate.send(SOURCE, reviewEvent(UUID.randomUUID(), UUID.randomUUID())
                .build().toByteArray()).get(10, TimeUnit.SECONDS);
        await(() -> controlledStore.successfulCreates() == successfulBefore + 1,
                Duration.ofSeconds(10));
        await(() -> committedOffset(0) == 4, Duration.ofSeconds(10));
        assertEquals(1, recoveryRecords(SOURCE + ":0:2").size());
    }

    private TransactIqDeadLetterPublishingRecoverer publisher() {
        TransactIqDeadLetterPublishingRecoverer publisher =
                new TransactIqDeadLetterPublishingRecoverer(kafkaTemplate,
                        (record, exception) -> new TopicPartition(DLT, record.partition()));
        publisher.setHeadersFunction(new RecoveryHeaders(failurePolicy, GROUP, clock));
        publisher.setVerifyPartition(true);
        publisher.setThrowIfNoDestinationReturned(true);
        publisher.setFailIfSendResultIsError(true);
        publisher.excludeHeader(HeadersToAdd.EXCEPTION, HeadersToAdd.EX_CAUSE,
                HeadersToAdd.EX_MSG, HeadersToAdd.EX_STACKTRACE);
        return publisher;
    }

    private void setDltMaximumMessageBytes(int bytes) throws Exception {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, DLT);
        AlterConfigOp operation = new AlterConfigOp(
                new ConfigEntry("max.message.bytes", Integer.toString(bytes)),
                AlterConfigOp.OpType.SET);
        try (AdminClient admin = admin()) {
            admin.incrementalAlterConfigs(Map.of(resource, List.of(operation)))
                    .all().get(10, TimeUnit.SECONDS);
        }
    }

    private List<ConsumerRecord<byte[], byte[]>> recoveryRecords(String recoveryId) {
        return readDlt().stream()
                .filter(record -> recoveryId.equals(header(record, RecoveryHeaders.RECOVERY_ID)))
                .toList();
    }

    private List<ConsumerRecord<byte[], byte[]>> readDlt() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-snapshot-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        List<ConsumerRecord<byte[], byte[]>> result = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(DLT));
            long quietDeadline = System.nanoTime() + Duration.ofMillis(700).toNanos();
            while (System.nanoTime() < quietDeadline) {
                var records = consumer.poll(Duration.ofMillis(100));
                records.forEach(result::add);
                if (!records.isEmpty()) {
                    quietDeadline = System.nanoTime() + Duration.ofMillis(300).toNanos();
                }
            }
        }
        return result;
    }

    private long committedOffset(int partition) {
        try (AdminClient admin = admin()) {
            var offsets = admin.listConsumerGroupOffsets(GROUP)
                    .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
            var offset = offsets.get(new TopicPartition(SOURCE, partition));
            return offset == null ? -1 : offset.offset();
        } catch (Exception unavailable) {
            return -1;
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String originalTopic(ConsumerRecord<?, ?> record) {
        return new String(record.headers().lastHeader("kafka_dlt-original-topic").value(),
                StandardCharsets.UTF_8);
    }

    private static int originalPartition(ConsumerRecord<?, ?> record) {
        return java.nio.ByteBuffer.wrap(record.headers()
                .lastHeader("kafka_dlt-original-partition").value()).getInt();
    }

    private static long originalOffset(ConsumerRecord<?, ?> record) {
        return java.nio.ByteBuffer.wrap(record.headers()
                .lastHeader("kafka_dlt-original-offset").value()).getLong();
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), "condition did not become true before timeout");
    }

    private static AdminClient admin() {
        return AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FaultInjectionConfiguration {

        @Bean
        @Primary
        ControlledFraudCaseStore controlledFraudCaseStore(JdbcFraudCaseStore delegate) {
            return new ControlledFraudCaseStore(delegate);
        }
    }

    static final class ControlledFraudCaseStore implements FraudCaseStore {

        private final JdbcFraudCaseStore delegate;
        private final AtomicReference<UUID> failingEvent = new AtomicReference<>();
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger successfulCreates = new AtomicInteger();

        ControlledFraudCaseStore(JdbcFraudCaseStore delegate) {
            this.delegate = delegate;
        }

        void failUnexpectedlyFor(UUID eventId) {
            failingEvent.set(eventId);
            attempts.set(0);
        }

        void clearFailure() {
            failingEvent.set(null);
        }

        int attempts() {
            return attempts.get();
        }

        int successfulCreates() {
            return successfulCreates.get();
        }

        @Override
        public CreationResult create(AuthorizationEventSnapshot event) {
            if (event.sourceEventId().equals(failingEvent.get())) {
                attempts.incrementAndGet();
                throw new IllegalStateException("synthetic unexpected processing failure");
            }
            CreationResult result = delegate.create(event);
            successfulCreates.incrementAndGet();
            return result;
        }
    }
}
