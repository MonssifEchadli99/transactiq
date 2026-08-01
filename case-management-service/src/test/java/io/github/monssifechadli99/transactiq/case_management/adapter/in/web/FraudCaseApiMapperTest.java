package io.github.monssifechadli99.transactiq.case_management.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseLifecycleEvent;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseResolutionOutcome;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FraudCaseApiMapperTest {
    private final FraudCaseApiMapper mapper = new FraudCaseApiMapper();

    @Test
    void mapsPersistedClaimAndResolutionHistoryWithoutInventingData() {
        Instant claimedAt = Instant.parse("2026-08-01T11:00:00Z");
        Instant resolvedAt = Instant.parse("2026-08-01T12:00:00Z");
        var response = mapper.toHistory(List.of(
                new FraudCaseLifecycleEvent(
                        UUID.randomUUID(), "CLAIMED", FraudCaseStatus.NEW,
                        FraudCaseStatus.IN_REVIEW, null, "analyst-a", "analyst-a", 1,
                        claimedAt, null, null),
                new FraudCaseLifecycleEvent(
                        UUID.randomUUID(), "RESOLVED", FraudCaseStatus.IN_REVIEW,
                        FraudCaseStatus.RESOLVED, "analyst-a", "analyst-a", "analyst-a", 2,
                        resolvedAt, FraudCaseResolutionOutcome.CONFIRMED_FRAUD,
                        "Synthetic rationale")));

        assertEquals(List.of("CLAIMED", "RESOLVED"),
                response.items().stream().map(item -> item.eventType()).toList());
        assertNull(response.items().getFirst().resolutionOutcome());
        assertEquals(FraudCaseResolutionOutcome.CONFIRMED_FRAUD,
                response.items().getLast().resolutionOutcome());
        assertEquals("Synthetic rationale", response.items().getLast().resolutionRationale());
    }
}
