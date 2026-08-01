package io.github.monssifechadli99.transactiq.case_management.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ResolutionRationaleTest {
    @Test
    void trimsAndAcceptsApprovedLength() {
        assertEquals("Synthetic reason", ResolutionRationale.normalize("  Synthetic reason  "));
        assertEquals("x".repeat(2_000), ResolutionRationale.normalize("x".repeat(2_000)));
    }

    @Test
    void rejectsMissingShortLongAndControlCharacters() {
        assertInvalid(null);
        assertInvalid("         ");
        assertInvalid("too short");
        assertInvalid("x".repeat(2_001));
        assertInvalid("synthetic\nreason");
    }

    private static void assertInvalid(String value) {
        assertEquals("INVALID_RESOLUTION_RATIONALE", assertThrows(
                InvalidFraudCaseRequestException.class,
                () -> ResolutionRationale.normalize(value)).code());
    }
}
