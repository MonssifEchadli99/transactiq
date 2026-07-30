package io.github.monssifechadli99.transactiq.fraud.adapter.out.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.fraud.application.port.out.VelocityAttemptRecorder;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentRequest;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudChannel;
import io.github.monssifechadli99.transactiq.fraud.domain.velocity.VelocityRequestConflictException;
import io.github.monssifechadli99.transactiq.fraud.domain.velocity.VelocitySnapshot;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = "fraud.grpc.port=0")
@Testcontainers
class RedisVelocityAttemptRecorderIntegrationTest {

    private static final int REDIS_PORT = 6379;
    private static final Instant START = Instant.parse("2026-07-19T10:00:00Z");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4.9-alpine3.21"))
            .withExposedPorts(REDIS_PORT);

    @Autowired
    private VelocityAttemptRecorder recorder;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
    }

    @BeforeEach
    void clearRedis() {
        try (var connection = Objects.requireNonNull(redisTemplate.getConnectionFactory()).getConnection()) {
            connection.serverCommands().flushAll();
        }
    }

    @Test
    void uniqueRequestsAccumulateAndIncludeTheCurrentAttempt() {
        String token = "tok_accumulate01";
        FraudAssessmentRequest firstRequest =
                request("accumulate-1", token, "100.00", "EUR", "DE");

        VelocitySnapshot first = recorder.recordAttemptAndGetSnapshot(
                firstRequest,
                START);
        VelocitySnapshot second = recorder.recordAttemptAndGetSnapshot(
                request("accumulate-2", token, "200.00", "EUR", "DE"),
                START.plusSeconds(1));

        assertEquals(1, first.transactionCount());
        assertEquals(new BigDecimal("100.00"), first.rollingAmount("EUR"));
        assertEquals(2, second.transactionCount());
        assertEquals(new BigDecimal("300.00"), second.rollingAmount("EUR"));
        assertEquals(Set.of("DE"), second.observedCountries());
        Set<String> keys = redisTemplate.keys("*");
        assertFalse(keys.stream().anyMatch(key -> key.contains(token)));
        Object storedRequestFingerprint = redisTemplate.opsForHash().get(
                "transactiq:fraud:velocity:dedup:" + firstRequest.requestId(),
                "fingerprint");
        assertTrue(Objects.toString(storedRequestFingerprint).matches("[0-9a-f]{64}"));
        assertFalse(Objects.toString(storedRequestFingerprint).contains(token));
    }

    @Test
    void identicalRetryCountsOnceAndReturnsItsOriginalSnapshot() {
        FraudAssessmentRequest originalRequest =
                request("retry-original", "tok_retryOriginal01", "100.00", "EUR", "DE");
        VelocitySnapshot original = recorder.recordAttemptAndGetSnapshot(originalRequest, START);
        recorder.recordAttemptAndGetSnapshot(
                request("retry-later", "tok_retryOriginal01", "200.00", "EUR", "FR"),
                START.plusSeconds(1));

        VelocitySnapshot retry = recorder.recordAttemptAndGetSnapshot(
                originalRequest,
                START.plusSeconds(30));

        assertEquals(original, retry);
        assertEquals(1, retry.transactionCount());
        assertEquals(new BigDecimal("100.00"), retry.rollingAmount("EUR"));
        assertEquals(Set.of("DE"), retry.observedCountries());
    }

    @Test
    void concurrentIdenticalRequestsProduceOneRecordedAttempt() throws Exception {
        FraudAssessmentRequest request =
                request("concurrent-identical", "tok_concurrentSame01", "10.00", "EUR", "DE");
        List<Callable<VelocitySnapshot>> tasks = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            tasks.add(() -> recorder.recordAttemptAndGetSnapshot(request, START));
        }

        List<VelocitySnapshot> snapshots = runConcurrently(tasks);

        assertEquals(12, snapshots.size());
        snapshots.forEach(snapshot -> {
            assertEquals(1, snapshot.transactionCount());
            assertEquals(new BigDecimal("10.00"), snapshot.rollingAmount("EUR"));
        });
    }

    @Test
    void concurrentUniqueRequestsHaveNoLostUpdates() throws Exception {
        List<Callable<VelocitySnapshot>> tasks = new ArrayList<>();
        for (int index = 1; index <= 20; index++) {
            int attempt = index;
            tasks.add(() -> recorder.recordAttemptAndGetSnapshot(
                    request(
                            "concurrent-unique-" + attempt,
                            "tok_concurrentUnique01",
                            "1.00",
                            "EUR",
                            "DE"),
                    START));
        }

        List<Long> observedCounts = runConcurrently(tasks).stream()
                .map(VelocitySnapshot::transactionCount)
                .sorted()
                .toList();

        assertEquals(
                java.util.stream.LongStream.rangeClosed(1, 20).boxed().toList(),
                observedCounts);
    }

    @Test
    void differentCardsAndCurrenciesRemainIsolated() {
        recorder.recordAttemptAndGetSnapshot(
                request("isolation-a-eur-1", "tok_isolationCardA", "100.00", "EUR", "DE"),
                START);
        VelocitySnapshot cardAUsd = recorder.recordAttemptAndGetSnapshot(
                request("isolation-a-usd", "tok_isolationCardA", "200.00", "USD", "DE"),
                START.plusSeconds(1));
        VelocitySnapshot cardBEur = recorder.recordAttemptAndGetSnapshot(
                request("isolation-b-eur", "tok_isolationCardB", "300.00", "EUR", "FR"),
                START.plusSeconds(1));
        VelocitySnapshot cardAEur = recorder.recordAttemptAndGetSnapshot(
                request("isolation-a-eur-2", "tok_isolationCardA", "50.00", "EUR", "DE"),
                START.plusSeconds(2));

        assertEquals(2, cardAUsd.transactionCount());
        assertEquals(new BigDecimal("200.00"), cardAUsd.rollingAmount("USD"));
        assertEquals(BigDecimal.ZERO, cardAUsd.rollingAmount("EUR"));
        assertEquals(1, cardBEur.transactionCount());
        assertEquals(new BigDecimal("300.00"), cardBEur.rollingAmount("EUR"));
        assertEquals(3, cardAEur.transactionCount());
        assertEquals(new BigDecimal("150.00"), cardAEur.rollingAmount("EUR"));
    }

    @Test
    void eventExactlyAtEachCutoffIsExpiredAndPruned() {
        recorder.recordAttemptAndGetSnapshot(
                request("cutoff-count-first", "tok_countCutoff01", "10.00", "EUR", "DE"),
                START);
        VelocitySnapshot countCutoff = recorder.recordAttemptAndGetSnapshot(
                request("cutoff-count-current", "tok_countCutoff01", "10.00", "EUR", "DE"),
                START.plusSeconds(60));

        recorder.recordAttemptAndGetSnapshot(
                request("cutoff-amount-first", "tok_amountCutoff01", "100.00", "EUR", "DE"),
                START);
        VelocitySnapshot amountCutoff = recorder.recordAttemptAndGetSnapshot(
                request("cutoff-amount-current", "tok_amountCutoff01", "25.00", "EUR", "DE"),
                START.plusSeconds(300));

        recorder.recordAttemptAndGetSnapshot(
                request("cutoff-country-first", "tok_countryCutoff01", "10.00", "EUR", "DE"),
                START);
        VelocitySnapshot countryCutoff = recorder.recordAttemptAndGetSnapshot(
                request("cutoff-country-current", "tok_countryCutoff01", "10.00", "EUR", "FR"),
                START.plusSeconds(600));

        assertEquals(1, countCutoff.transactionCount());
        assertEquals(new BigDecimal("25.00"), amountCutoff.rollingAmount("EUR"));
        assertEquals(Set.of("FR"), countryCutoff.observedCountries());
    }

    @Test
    void conflictingReuseOfRequestIdIsRejected() {
        FraudAssessmentRequest original =
                request("conflict", "tok_conflictRequest01", "10.00", "EUR", "DE");
        FraudAssessmentRequest changed =
                request("conflict", "tok_conflictRequest01", "11.00", "EUR", "DE");
        recorder.recordAttemptAndGetSnapshot(original, START);

        assertThrows(
                VelocityRequestConflictException.class,
                () -> recorder.recordAttemptAndGetSnapshot(changed, START.plusSeconds(1)));
    }

    @Test
    void monetaryAggregationRemainsExactBigDecimalArithmetic() {
        String token = "tok_exactAmount01";
        recorder.recordAttemptAndGetSnapshot(
                request("exact-amount-1", token, "0.10", "EUR", "DE"),
                START);

        VelocitySnapshot snapshot = recorder.recordAttemptAndGetSnapshot(
                request("exact-amount-2", token, "0.20", "EUR", "DE"),
                START.plusSeconds(1));

        assertEquals(new BigDecimal("0.30"), snapshot.rollingAmount("EUR"));
    }

    private static FraudAssessmentRequest request(
            String requestSeed,
            String cardToken,
            String amount,
            String currency,
            String country) {
        return new FraudAssessmentRequest(
                UUID.nameUUIDFromBytes(requestSeed.getBytes(StandardCharsets.UTF_8)),
                cardToken,
                "merchant-123",
                "5732",
                new BigDecimal(amount),
                currency,
                country,
                FraudChannel.ECOMMERCE,
                Instant.parse("2026-07-19T09:00:00Z"));
    }

    private static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = tasks.stream()
                    .map(task -> executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        return task.call();
                    }))
                    .toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
