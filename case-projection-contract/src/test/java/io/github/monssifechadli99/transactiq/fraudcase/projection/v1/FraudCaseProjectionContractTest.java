package io.github.monssifechadli99.transactiq.fraudcase.projection.v1;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudCaseProjectionSnapshot;
import org.junit.jupiter.api.Test;

class FraudCaseProjectionContractTest {
    @Test void nullableFieldsArePresenceAware() {
        var snapshot=FraudCaseProjectionSnapshot.newBuilder().setCaseId("synthetic-case").build();
        assertFalse(snapshot.hasAssigneeId());
        assertFalse(snapshot.hasDeclineReason());
        assertFalse(snapshot.hasResolutionOutcome());
        assertFalse(snapshot.hasResolvedAt());
        assertTrue(snapshot.toString().contains("synthetic-case"));
    }
}
