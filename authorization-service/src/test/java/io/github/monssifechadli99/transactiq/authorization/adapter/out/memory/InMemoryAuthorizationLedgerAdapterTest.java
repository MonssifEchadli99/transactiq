package io.github.monssifechadli99.transactiq.authorization.adapter.out.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.monssifechadli99.transactiq.authorization.adapter.out.memory.InMemoryAuthorizationLedgerAdapter.LedgerEntry;
import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationChannel;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationOutcome;
import io.github.monssifechadli99.transactiq.authorization.domain.DeclineReason;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessment;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessmentResult;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudRuleMatch;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudRuleSeverity;
import io.github.monssifechadli99.transactiq.authorization.domain.NonFraudCheckResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryAuthorizationLedgerAdapterTest {

    private final InMemoryAuthorizationLedgerAdapter adapter =
            new InMemoryAuthorizationLedgerAdapter();

    @Test
    void appendsApprovedAndDeclinedEntriesToReadOnlySnapshot() {
        AuthorizationCommand approvedCommand = command(
                UUID.fromString("d5e75b60-a263-4f76-b5d0-a35f1a09bc67"));
        AuthorizationCommand declinedCommand = command(
                UUID.fromString("7ca1739b-1eca-4330-ae0c-f68826e10256"));
        AuthorizationOutcome approved = new AuthorizationOutcome.Approved(false);
        AuthorizationOutcome declined =
                new AuthorizationOutcome.Declined(DeclineReason.HIGH_FRAUD_RISK, true);

        adapter.record(
                approvedCommand,
                FraudAssessmentResult.clear(),
                NonFraudCheckResult.PASSED,
                approved);
        adapter.record(
                declinedCommand,
                highRiskAssessment(),
                NonFraudCheckResult.PASSED,
                declined);

        List<LedgerEntry> snapshot = adapter.snapshot();
        assertEquals(
                List.of(
                        new LedgerEntry(
                                approvedCommand,
                                FraudAssessmentResult.clear(),
                                NonFraudCheckResult.PASSED,
                                approved),
                        new LedgerEntry(
                                declinedCommand,
                                highRiskAssessment(),
                                NonFraudCheckResult.PASSED,
                                declined)),
                snapshot);
        assertInstanceOf(AuthorizationOutcome.Approved.class, snapshot.get(0).outcome());
        assertInstanceOf(AuthorizationOutcome.Declined.class, snapshot.get(1).outcome());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.add(new LedgerEntry(
                        approvedCommand,
                        FraudAssessmentResult.clear(),
                        NonFraudCheckResult.PASSED,
                        approved)));
    }

    private static FraudAssessmentResult highRiskAssessment() {
        return new FraudAssessmentResult(
                FraudAssessment.HIGH_RISK,
                70,
                List.of(new FraudRuleMatch(
                        "TEST_HIGH_RISK",
                        FraudRuleSeverity.HIGH_RISK,
                        "Synthetic high-risk evidence",
                        70)));
    }

    @Test
    void preservesSeparateEntriesForDuplicateRequestIds() {
        UUID requestId = UUID.fromString("d5e75b60-a263-4f76-b5d0-a35f1a09bc67");
        AuthorizationCommand command = command(requestId);
        AuthorizationOutcome outcome = new AuthorizationOutcome.Approved(false);

        adapter.record(
                command,
                FraudAssessmentResult.clear(),
                NonFraudCheckResult.PASSED,
                outcome);
        adapter.record(
                command,
                FraudAssessmentResult.clear(),
                NonFraudCheckResult.PASSED,
                outcome);

        List<LedgerEntry> snapshot = adapter.snapshot();
        assertEquals(2, snapshot.size());
        assertEquals(requestId, snapshot.get(0).command().requestId());
        assertEquals(requestId, snapshot.get(1).command().requestId());
    }

    private static AuthorizationCommand command(UUID requestId) {
        return new AuthorizationCommand(
                requestId,
                "tok_A1B2C3D4",
                "merchant-standard",
                "5411",
                new BigDecimal("42.50"),
                "EUR",
                "DE",
                AuthorizationChannel.ECOMMERCE,
                Instant.parse("2026-07-19T10:15:30Z"));
    }
}
