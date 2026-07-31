package io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.kafka.support.KafkaHeaders;

public final class RecoveryHeaders implements BiFunction<ConsumerRecord<?, ?>, Exception, Headers> {

    public static final String RECOVERY_ID = "transactiq-recovery-id";
    public static final String CATEGORY = "transactiq-recovery-category";
    public static final String EXCEPTION_CLASS = "transactiq-recovery-exception-class";
    public static final String RECOVERY_AT = "transactiq-recovery-at";
    public static final String ATTEMPT = "transactiq-recovery-attempt";
    public static final String CONSUMER_GROUP = "transactiq-recovery-consumer-group";
    public static final String PAYLOAD_SHA256 = "transactiq-recovery-payload-sha256";
    public static final List<String> NAMES = List.of(
            RECOVERY_ID,
            CATEGORY,
            EXCEPTION_CLASS,
            RECOVERY_AT,
            ATTEMPT,
            CONSUMER_GROUP,
            PAYLOAD_SHA256);

    private final KafkaFailurePolicy failurePolicy;
    private final String consumerGroup;
    private final Clock clock;

    public RecoveryHeaders(
            KafkaFailurePolicy failurePolicy, String consumerGroup, Clock clock) {
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy must not be null");
        if (consumerGroup == null || consumerGroup.isBlank()) {
            throw new IllegalArgumentException("consumerGroup must not be blank");
        }
        this.consumerGroup = consumerGroup;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Headers apply(ConsumerRecord<?, ?> record, Exception exception) {
        Objects.requireNonNull(record, "record must not be null");
        Objects.requireNonNull(exception, "exception must not be null");
        RecordHeaders headers = new RecordHeaders();
        add(headers, RECOVERY_ID, recoveryId(record));
        add(headers, CATEGORY, failurePolicy.recoveryCategory(exception).name());
        add(headers, EXCEPTION_CLASS, failurePolicy.diagnosticException(exception).getClass().getName());
        add(headers, RECOVERY_AT, DateTimeFormatter.ISO_INSTANT.format(clock.instant()));
        add(headers, ATTEMPT, Integer.toString(deliveryAttempt(record)));
        add(headers, CONSUMER_GROUP, consumerGroup);
        add(headers, PAYLOAD_SHA256, sha256((byte[]) record.value()));
        return headers;
    }

    public static String recoveryId(ConsumerRecord<?, ?> record) {
        return record.topic() + ":" + record.partition() + ":" + record.offset();
    }

    static int deliveryAttempt(ConsumerRecord<?, ?> record) {
        Header header = record.headers().lastHeader(KafkaHeaders.DELIVERY_ATTEMPT);
        if (header == null || header.value() == null || header.value().length != Integer.BYTES) {
            return 1;
        }
        return ByteBuffer.wrap(header.value()).getInt();
    }

    private static String sha256(byte[] value) {
        try {
            byte[] bytes = value == null ? new byte[0] : value;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 must be available", unavailable);
        }
    }

    private static void add(Headers headers, String name, String value) {
        headers.add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
