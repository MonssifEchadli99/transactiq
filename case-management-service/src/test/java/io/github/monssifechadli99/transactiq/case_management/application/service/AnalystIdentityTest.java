package io.github.monssifechadli99.transactiq.case_management.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AnalystIdentityTest {
    @Test
    void trimsButPreservesCase() {
        assertEquals("Analyst-A", AnalystIdentity.required("  Analyst-A  "));
    }

    @Test
    void rejectsMissingBlankLongAndControlCharacters() {
        assertThrows(InvalidFraudCaseRequestException.class, () -> AnalystIdentity.required(null));
        assertThrows(InvalidFraudCaseRequestException.class, () -> AnalystIdentity.required("  "));
        assertThrows(InvalidFraudCaseRequestException.class,
                () -> AnalystIdentity.required("a".repeat(129)));
        assertThrows(InvalidFraudCaseRequestException.class,
                () -> AnalystIdentity.required("analyst\nother"));
    }
}
