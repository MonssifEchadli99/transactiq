package io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.monssifechadli99.transactiq.case_management.domain.InvalidAuthorizationEventException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;

class RecoveryHeadersTest {

    @Test
    void createsTheApprovedDeterministicRecoveryMetadata() {
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(
                "source", 2, 41, new byte[] {3, 4}, "payload".getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaHeaders.DELIVERY_ATTEMPT, ByteBuffer.allocate(4).putInt(5).array());
        RecoveryHeaders factory = new RecoveryHeaders(
                new KafkaFailurePolicy(), "case-group",
                Clock.fixed(Instant.parse("2026-07-31T10:15:30Z"), ZoneOffset.UTC));

        var headers = factory.apply(record, new InvalidAuthorizationEventException("secret"));

        assertEquals("source:2:41", value(headers.lastHeader(RecoveryHeaders.RECOVERY_ID)));
        assertEquals("INVALID_EVENT", value(headers.lastHeader(RecoveryHeaders.CATEGORY)));
        assertEquals(InvalidAuthorizationEventException.class.getName(),
                value(headers.lastHeader(RecoveryHeaders.EXCEPTION_CLASS)));
        assertEquals("2026-07-31T10:15:30Z", value(headers.lastHeader(RecoveryHeaders.RECOVERY_AT)));
        assertEquals("5", value(headers.lastHeader(RecoveryHeaders.ATTEMPT)));
        assertEquals("case-group", value(headers.lastHeader(RecoveryHeaders.CONSUMER_GROUP)));
        assertEquals("239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5",
                value(headers.lastHeader(RecoveryHeaders.PAYLOAD_SHA256)));
        RecoveryHeaders.NAMES.forEach(name -> {
            int count = 0;
            for (Header ignored : headers.headers(name)) {
                count++;
            }
            assertEquals(1, count);
        });
    }

    private static String value(Header header) {
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
