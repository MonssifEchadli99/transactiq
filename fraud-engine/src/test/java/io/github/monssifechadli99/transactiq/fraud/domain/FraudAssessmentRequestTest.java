package io.github.monssifechadli99.transactiq.fraud.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FraudAssessmentRequestTest {

    private static final UUID REQUEST_ID = UUID.fromString("f2b1c9d0-6e3a-4c1b-9b7a-2b6a1e9c7d44");
    private static final BigDecimal AMOUNT = new BigDecimal("1200.00");
    private static final Instant TRANSACTION_TIME = Instant.parse("2026-07-19T10:15:30Z");

    @Test
    void createsValidRequestWithEveryFieldPreserved() {
        FraudAssessmentRequest request = new FraudAssessmentRequest(
                REQUEST_ID,
                "tok_A1B2C3D4",
                "merchant-123",
                "5732",
                AMOUNT,
                "EUR",
                "DE",
                FraudChannel.ECOMMERCE,
                TRANSACTION_TIME);

        assertEquals(REQUEST_ID, request.requestId());
        assertEquals("tok_A1B2C3D4", request.cardToken());
        assertEquals("merchant-123", request.merchantId());
        assertEquals("5732", request.merchantCategoryCode());
        assertEquals(AMOUNT, request.amount());
        assertEquals("EUR", request.currency());
        assertEquals("DE", request.country());
        assertEquals(FraudChannel.ECOMMERCE, request.channel());
        assertEquals(TRANSACTION_TIME, request.transactionTime());
    }

    @Test
    void rejectsNullRequestId() {
        assertThrows(
                NullPointerException.class,
                () -> new FraudAssessmentRequest(
                        null, "tok_A1B2C3D4", "merchant-123", "5732", AMOUNT,
                        "EUR", "DE", FraudChannel.ECOMMERCE, TRANSACTION_TIME));
    }

    @Test
    void rejectsNullAmount() {
        assertThrows(
                NullPointerException.class,
                () -> new FraudAssessmentRequest(
                        REQUEST_ID, "tok_A1B2C3D4", "merchant-123", "5732", null,
                        "EUR", "DE", FraudChannel.ECOMMERCE, TRANSACTION_TIME));
    }

    @Test
    void rejectsNullChannel() {
        assertThrows(
                NullPointerException.class,
                () -> new FraudAssessmentRequest(
                        REQUEST_ID, "tok_A1B2C3D4", "merchant-123", "5732", AMOUNT,
                        "EUR", "DE", null, TRANSACTION_TIME));
    }

    @Test
    void rejectsNullTransactionTime() {
        assertThrows(
                NullPointerException.class,
                () -> new FraudAssessmentRequest(
                        REQUEST_ID, "tok_A1B2C3D4", "merchant-123", "5732", AMOUNT,
                        "EUR", "DE", FraudChannel.ECOMMERCE, null));
    }
}
