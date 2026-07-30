package io.github.monssifechadli99.transactiq.fraud.adapter.out.redis;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentRequest;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudChannel;
import io.github.monssifechadli99.transactiq.fraud.domain.velocity.VelocityStoreUnavailableException;
import io.github.monssifechadli99.transactiq.fraud.domain.velocity.VelocityTrackingSettings;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

class RedisVelocityAttemptRecorderFailureTest {

    @Test
    void unavailableRedisClientIsNotConvertedIntoAVelocitySnapshot() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/record_velocity_attempt.lua"));
        script.setResultType(List.class);
        RedisVelocityAttemptRecorder recorder = new RedisVelocityAttemptRecorder(
                new StringRedisTemplate(),
                script,
                new VelocityTrackingSettings(
                        Duration.ofSeconds(60),
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(10),
                        Duration.ofHours(24)));

        assertThrows(
                VelocityStoreUnavailableException.class,
                () -> recorder.recordAttemptAndGetSnapshot(request(), Instant.parse("2026-07-19T10:16:00Z")));
    }

    private static FraudAssessmentRequest request() {
        return new FraudAssessmentRequest(
                UUID.fromString("f2b1c9d0-6e3a-4c1b-9b7a-2b6a1e9c7d44"),
                "tok_A1B2C3D4",
                "merchant-123",
                "5732",
                new BigDecimal("10.00"),
                "EUR",
                "DE",
                FraudChannel.ECOMMERCE,
                Instant.parse("2026-07-19T10:15:30Z"));
    }
}
