package io.github.monssifechadli99.transactiq.fraud.adapter.out.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentRequest;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudChannel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FraudRequestFingerprintsTest {

    private static final UUID REQUEST_ID = UUID.fromString("f2b1c9d0-6e3a-4c1b-9b7a-2b6a1e9c7d44");
    private static final Instant TRANSACTION_TIME = Instant.parse("2026-07-19T10:15:30.123456789Z");

    @Test
    void tokenFingerprintIsStableSha256AndDoesNotRevealTheToken() {
        String token = "tok_A1B2C3D4";

        String fingerprint = FraudRequestFingerprints.tokenFingerprint(token);

        assertEquals(fingerprint, FraudRequestFingerprints.tokenFingerprint(token));
        assertTrue(fingerprint.matches("[0-9a-f]{64}"));
        assertNotEquals(token, fingerprint);
    }

    @Test
    void numericallyEqualAmountsHaveTheSameCanonicalRequestFingerprint() {
        assertEquals(
                FraudRequestFingerprints.requestFingerprint(request(new BigDecimal("10.00"))),
                FraudRequestFingerprints.requestFingerprint(request(new BigDecimal("10.0"))));
    }

    @Test
    void everyFraudRelevantRequestFieldContributesToTheFingerprint() {
        FraudAssessmentRequest original = request(new BigDecimal("10.00"));
        String originalFingerprint = FraudRequestFingerprints.requestFingerprint(original);
        List<FraudAssessmentRequest> changedRequests = List.of(
                copy(UUID.fromString("a31cc3ad-6115-4a96-a4bf-8a83ec44ef48"),
                        original.cardToken(), original.merchantId(), original.merchantCategoryCode(),
                        original.amount(), original.currency(), original.country(), original.channel(),
                        original.transactionTime()),
                copy(original.requestId(), "tok_changed001", original.merchantId(),
                        original.merchantCategoryCode(), original.amount(), original.currency(),
                        original.country(), original.channel(), original.transactionTime()),
                copy(original.requestId(), original.cardToken(), "merchant-changed",
                        original.merchantCategoryCode(), original.amount(), original.currency(),
                        original.country(), original.channel(), original.transactionTime()),
                copy(original.requestId(), original.cardToken(), original.merchantId(),
                        "7995", original.amount(), original.currency(), original.country(),
                        original.channel(), original.transactionTime()),
                copy(original.requestId(), original.cardToken(), original.merchantId(),
                        original.merchantCategoryCode(), new BigDecimal("11.00"), original.currency(),
                        original.country(), original.channel(), original.transactionTime()),
                copy(original.requestId(), original.cardToken(), original.merchantId(),
                        original.merchantCategoryCode(), original.amount(), "USD", original.country(),
                        original.channel(), original.transactionTime()),
                copy(original.requestId(), original.cardToken(), original.merchantId(),
                        original.merchantCategoryCode(), original.amount(), original.currency(), "FR",
                        original.channel(), original.transactionTime()),
                copy(original.requestId(), original.cardToken(), original.merchantId(),
                        original.merchantCategoryCode(), original.amount(), original.currency(),
                        original.country(), FraudChannel.POINT_OF_SALE, original.transactionTime()),
                copy(original.requestId(), original.cardToken(), original.merchantId(),
                        original.merchantCategoryCode(), original.amount(), original.currency(),
                        original.country(), original.channel(), original.transactionTime().plusNanos(1)));

        changedRequests.forEach(changed -> assertNotEquals(
                originalFingerprint,
                FraudRequestFingerprints.requestFingerprint(changed)));
    }

    private static FraudAssessmentRequest request(BigDecimal amount) {
        return new FraudAssessmentRequest(
                REQUEST_ID,
                "tok_A1B2C3D4",
                "merchant-123",
                "5732",
                amount,
                "EUR",
                "DE",
                FraudChannel.ECOMMERCE,
                TRANSACTION_TIME);
    }

    private static FraudAssessmentRequest copy(
            UUID requestId,
            String cardToken,
            String merchantId,
            String merchantCategoryCode,
            BigDecimal amount,
            String currency,
            String country,
            FraudChannel channel,
            Instant transactionTime) {
        return new FraudAssessmentRequest(
                requestId,
                cardToken,
                merchantId,
                merchantCategoryCode,
                amount,
                currency,
                country,
                channel,
                transactionTime);
    }
}
