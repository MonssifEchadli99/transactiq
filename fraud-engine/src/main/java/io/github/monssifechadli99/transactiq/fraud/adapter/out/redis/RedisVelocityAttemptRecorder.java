package io.github.monssifechadli99.transactiq.fraud.adapter.out.redis;

import io.github.monssifechadli99.transactiq.fraud.application.port.out.VelocityAttemptRecorder;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentRequest;
import io.github.monssifechadli99.transactiq.fraud.domain.velocity.VelocityRequestConflictException;
import io.github.monssifechadli99.transactiq.fraud.domain.velocity.VelocitySnapshot;
import io.github.monssifechadli99.transactiq.fraud.domain.velocity.VelocityStoreUnavailableException;
import io.github.monssifechadli99.transactiq.fraud.domain.velocity.VelocityTrackingSettings;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

public final class RedisVelocityAttemptRecorder implements VelocityAttemptRecorder {

    private static final String KEY_PREFIX = "transactiq:fraud:velocity:";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> recordAttemptScript;
    private final VelocityTrackingSettings settings;

    public RedisVelocityAttemptRecorder(
            StringRedisTemplate redisTemplate,
            RedisScript<List> recordAttemptScript,
            VelocityTrackingSettings settings) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.recordAttemptScript = Objects.requireNonNull(
                recordAttemptScript,
                "recordAttemptScript must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
    }

    @Override
    public VelocitySnapshot recordAttemptAndGetSnapshot(
            FraudAssessmentRequest request,
            Instant observedAt) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");

        String tokenFingerprint = FraudRequestFingerprints.tokenFingerprint(request.cardToken());
        String currencyFingerprint = FraudRequestFingerprints.keyComponentFingerprint(request.currency());
        List<String> keys = List.of(
                KEY_PREFIX + "dedup:" + request.requestId(),
                KEY_PREFIX + "count:" + tokenFingerprint,
                KEY_PREFIX + "amount:" + tokenFingerprint + ":" + currencyFingerprint,
                KEY_PREFIX + "country:" + tokenFingerprint);

        long observedAtMillis = observedAt.toEpochMilli();
        List<?> result;
        try {
            result = redisTemplate.execute(
                    recordAttemptScript,
                    keys,
                    FraudRequestFingerprints.requestFingerprint(request),
                    Long.toString(observedAtMillis),
                    Long.toString(cutoff(observedAtMillis, settings.transactionCountWindow())),
                    Long.toString(cutoff(observedAtMillis, settings.rollingAmountWindow())),
                    Long.toString(cutoff(observedAtMillis, settings.countrySwitchWindow())),
                    request.requestId().toString(),
                    request.amount().toPlainString(),
                    request.country(),
                    Long.toString(settings.transactionCountWindow().toMillis()),
                    Long.toString(settings.rollingAmountWindow().toMillis()),
                    Long.toString(settings.countrySwitchWindow().toMillis()),
                    Long.toString(settings.deduplicationRetention().toMillis()));
        } catch (RuntimeException exception) {
            throw new VelocityStoreUnavailableException(exception);
        }

        if (result == null || result.isEmpty()) {
            throw new VelocityStoreUnavailableException(
                    new IllegalStateException("velocity store returned no result"));
        }
        String status = asString(result.getFirst());
        if ("CONFLICT".equals(status)) {
            throw new VelocityRequestConflictException();
        }
        if (!"RECORDED".equals(status) && !"DUPLICATE".equals(status)) {
            throw new VelocityStoreUnavailableException(
                    new IllegalStateException("velocity store returned an invalid status"));
        }

        try {
            return toSnapshot(request.currency(), result);
        } catch (RuntimeException exception) {
            throw new VelocityStoreUnavailableException(exception);
        }
    }

    private static long cutoff(long observedAtMillis, Duration window) {
        return Math.subtractExact(observedAtMillis, window.toMillis());
    }

    private static VelocitySnapshot toSnapshot(String currency, List<?> result) {
        if (result.size() != 5) {
            throw new IllegalStateException("velocity store returned an invalid result");
        }

        long transactionCount = Long.parseLong(asString(result.get(1)));
        if (transactionCount < 1) {
            throw new IllegalStateException("velocity store returned an invalid transaction count");
        }
        String encodedAmounts = requireStoredValue(result.get(2), "amounts");
        String encodedCountries = requireStoredValue(result.get(3), "countries");
        BigDecimal rollingTotal = Arrays.stream(encodedAmounts.split(","))
                .filter(value -> !value.isEmpty())
                .map(BigDecimal::new)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Set<String> countries = new LinkedHashSet<>(Arrays.stream(encodedCountries.split(","))
                .filter(value -> !value.isEmpty())
                .toList());
        Instant observedAt = Instant.ofEpochMilli(Long.parseLong(asString(result.get(4))));
        return new VelocitySnapshot(
                observedAt,
                transactionCount,
                Map.of(currency, rollingTotal),
                countries);
    }

    private static String asString(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return Objects.toString(value, null);
    }

    private static String requireStoredValue(Object value, String fieldName) {
        String storedValue = asString(value);
        if (storedValue == null || storedValue.isBlank()) {
            throw new IllegalStateException("velocity store returned invalid " + fieldName);
        }
        return storedValue;
    }
}
