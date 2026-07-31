package io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka;

import static io.github.monssifechadli99.transactiq.case_management.support.AuthorizationEventFixtures.reviewEvent;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(properties = {
    "fraud-case.consumer.topic=case-recovery-source",
    "fraud-case.consumer.dlt-topic=case-recovery-source.dlt",
    "fraud-case.consumer.group-id=case-recovery-integration",
    "fraud-case.consumer.retry-initial-interval=10ms",
    "fraud-case.consumer.retry-maximum-interval=30ms",
    "fraud-case.consumer.recovery-retry-interval=10ms"
})
class KafkaRecoveryIntegrationTest {

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

    @Autowired
    KafkaTemplate<byte[], byte[]> kafkaTemplate;

    @Autowired
    JdbcClient jdbcClient;

    @Test
    void poisonRecordIsQuarantinedByteForByteBeforeAHealthyRecordContinues() throws Exception {
        byte[] key = new byte[] {0, (byte) 0xff, 2};
        byte[] malformed = new byte[] {1, 2, 3};
        ProducerRecord<byte[], byte[]> poison =
                new ProducerRecord<>("case-recovery-source", 0, key, malformed);
        poison.headers().add("synthetic-correlation", new byte[] {7, 8});
        kafkaTemplate.send(poison).get(10, TimeUnit.SECONDS);

        ConsumerRecord<byte[], byte[]> recovered = consumeOne("case-recovery-source.dlt");

        assertEquals(0, recovered.partition());
        assertArrayEquals(key, recovered.key());
        assertArrayEquals(malformed, recovered.value());
        assertArrayEquals(new byte[] {7, 8}, recovered.headers()
                .lastHeader("synthetic-correlation").value());
        assertEquals("case-recovery-source:0:0",
                header(recovered, RecoveryHeaders.RECOVERY_ID));
        assertEquals("INVALID_EVENT", header(recovered, RecoveryHeaders.CATEGORY));
        assertEquals("1", header(recovered, RecoveryHeaders.ATTEMPT));
        assertNotNull(recovered.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC));
        assertNotNull(recovered.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION));
        assertNotNull(recovered.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET));
        assertNotNull(recovered.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP));
        RecoveryHeaders.NAMES.forEach(name -> assertEquals(1, count(recovered, name)));
        assertFalse(headerNames(recovered).stream().anyMatch(name ->
                name.contains("exception-message") || name.contains("exception-stacktrace")));

        byte[] healthy = reviewEvent(UUID.randomUUID(), UUID.randomUUID()).build().toByteArray();
        kafkaTemplate.send("case-recovery-source", UUID.randomUUID().toString()
                .getBytes(StandardCharsets.UTF_8), healthy).get(10, TimeUnit.SECONDS);

        await(() -> jdbcClient.sql("select count(*) from fraud_case.fraud_cases")
                .query(Integer.class).single() == 1, Duration.ofSeconds(20));
        await(() -> committedOffset() >= 2, Duration.ofSeconds(20));
        assertEquals(1, jdbcClient.sql("select count(*) from fraud_case.fraud_cases")
                .query(Integer.class).single());
    }

    private ConsumerRecord<byte[], byte[]> consumeOne(String topic) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-inspector-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) {
                var records = consumer.poll(Duration.ofMillis(250));
                if (!records.isEmpty()) {
                    return records.iterator().next();
                }
            }
        }
        throw new AssertionError("No DLT record received");
    }

    private long committedOffset() {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            var offsets = admin.listConsumerGroupOffsets("case-recovery-integration")
                    .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
            var offset = offsets.get(new TopicPartition("case-recovery-source", 0));
            return offset == null ? -1 : offset.offset();
        } catch (Exception unavailable) {
            return -1;
        }
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), "condition did not become true before timeout");
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        assertNotNull(header, "missing header " + name);
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private static int count(ConsumerRecord<?, ?> record, String name) {
        int count = 0;
        for (Header ignored : record.headers().headers(name)) {
            count++;
        }
        return count;
    }

    private static List<String> headerNames(ConsumerRecord<?, ?> record) {
        return java.util.stream.StreamSupport.stream(record.headers().spliterator(), false)
                .map(Header::key).toList();
    }
}
