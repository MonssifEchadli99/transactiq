package io.github.monssifechadli99.transactiq.case_management.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FraudCaseCursorCodecTest {
    private final FraudCaseCursorCodec codec = new FraudCaseCursorCodec();

    @Test
    void roundTripsCreatedAtAndCaseId() {
        Instant createdAt = Instant.parse("2026-07-31T10:15:30.123456Z");
        UUID caseId = UUID.fromString("30000000-0000-4000-8000-000000000001");

        assertEquals(new FraudCaseCursorCodec.Cursor(createdAt, caseId),
                codec.decode(codec.encode(createdAt, caseId)));
    }

    @Test
    void rejectsMalformedCursor() {
        assertThrows(InvalidFraudCaseRequestException.class, () -> codec.decode("not-a-cursor"));
    }
}
