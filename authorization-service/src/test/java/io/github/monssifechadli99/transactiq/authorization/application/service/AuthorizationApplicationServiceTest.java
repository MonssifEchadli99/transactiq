package io.github.monssifechadli99.transactiq.authorization.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationChannel;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.AuthorizationLedgerPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.FraudAssessmentPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.NonFraudCheckPort;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationDecision;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationOutcome;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationPolicy;
import io.github.monssifechadli99.transactiq.authorization.domain.DeclineReason;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessment;
import io.github.monssifechadli99.transactiq.authorization.domain.NonFraudCheckResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthorizationApplicationServiceTest {

    private static final AuthorizationCommand COMMAND = new AuthorizationCommand(
            UUID.fromString("d5e75b60-a263-4f76-b5d0-a35f1a09bc67"),
            "tok_A1B2C3D4",
            "merchant-123",
            "5411",
            new BigDecimal("42.50"),
            "EUR",
            "DE",
            AuthorizationChannel.ECOMMERCE,
            Instant.parse("2026-07-19T10:15:30Z"));

    @Test
    void clearAndPassedIsApprovedAndRecordedOnce() {
        TestFixture fixture = fixture(FraudAssessment.CLEAR, NonFraudCheckResult.PASSED);

        AuthorizationOutcome outcome = fixture.service().authorize(COMMAND);

        assertInstanceOf(AuthorizationOutcome.Approved.class, outcome);
        assertEquals(AuthorizationDecision.APPROVED, outcome.decision());
        assertRecordedOnce(fixture.ledger(), outcome);
    }

    @Test
    void reviewAndPassedIsDeclinedAndRecordedOnce() {
        TestFixture fixture = fixture(FraudAssessment.REVIEW, NonFraudCheckResult.PASSED);

        AuthorizationOutcome outcome = fixture.service().authorize(COMMAND);

        AuthorizationOutcome.Declined declined =
                assertInstanceOf(AuthorizationOutcome.Declined.class, outcome);
        assertEquals(AuthorizationDecision.DECLINED, declined.decision());
        assertEquals(DeclineReason.FRAUD_REVIEW_REQUIRED, declined.declineReason());
        assertRecordedOnce(fixture.ledger(), outcome);
    }

    @Test
    void highRiskAndInsufficientFundsPreservesPrimaryReasonAndRecordsOnce() {
        TestFixture fixture =
                fixture(FraudAssessment.HIGH_RISK, NonFraudCheckResult.INSUFFICIENT_FUNDS);

        AuthorizationOutcome outcome = fixture.service().authorize(COMMAND);

        AuthorizationOutcome.Declined declined =
                assertInstanceOf(AuthorizationOutcome.Declined.class, outcome);
        assertEquals(DeclineReason.INSUFFICIENT_FUNDS, declined.declineReason());
        assertTrue(declined.fraudCaseRequired());
        assertRecordedOnce(fixture.ledger(), outcome);
    }

    @Test
    void invokesBothAssessmentPortsForNormalRequest() {
        TestFixture fixture = fixture(FraudAssessment.CLEAR, NonFraudCheckResult.PASSED);

        fixture.service().authorize(COMMAND);

        assertEquals(1, fixture.fraudAssessmentPort().invocationCount());
        assertSame(COMMAND, fixture.fraudAssessmentPort().lastCommand());
        assertEquals(1, fixture.nonFraudCheckPort().invocationCount());
        assertSame(COMMAND, fixture.nonFraudCheckPort().lastCommand());
    }

    @Test
    void fraudAssessmentFailurePropagatesAndRecordsNothing() {
        TestFixture fixture = fixture(FraudAssessment.CLEAR, NonFraudCheckResult.PASSED);
        IllegalStateException failure = new IllegalStateException("fraud assessment unavailable");
        fixture.fraudAssessmentPort().failWith(failure);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> fixture.service().authorize(COMMAND));

        assertSame(failure, thrown);
        assertTrue(fixture.ledger().records().isEmpty());
    }

    @Test
    void nonFraudCheckFailurePropagatesAndRecordsNothing() {
        TestFixture fixture = fixture(FraudAssessment.CLEAR, NonFraudCheckResult.PASSED);
        IllegalStateException failure = new IllegalStateException("non-fraud check unavailable");
        fixture.nonFraudCheckPort().failWith(failure);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> fixture.service().authorize(COMMAND));

        assertSame(failure, thrown);
        assertTrue(fixture.ledger().records().isEmpty());
    }

    private static TestFixture fixture(
            FraudAssessment fraudAssessment,
            NonFraudCheckResult nonFraudCheckResult) {
        FakeFraudAssessmentPort fraudAssessmentPort =
                new FakeFraudAssessmentPort(fraudAssessment);
        FakeNonFraudCheckPort nonFraudCheckPort =
                new FakeNonFraudCheckPort(nonFraudCheckResult);
        RecordingAuthorizationLedger ledger = new RecordingAuthorizationLedger();
        AuthorizationApplicationService service = new AuthorizationApplicationService(
                fraudAssessmentPort,
                nonFraudCheckPort,
                ledger,
                new AuthorizationPolicy());
        return new TestFixture(service, fraudAssessmentPort, nonFraudCheckPort, ledger);
    }

    private static void assertRecordedOnce(
            RecordingAuthorizationLedger ledger,
            AuthorizationOutcome outcome) {
        assertEquals(1, ledger.records().size());
        RecordedAuthorization recorded = ledger.records().getFirst();
        assertSame(COMMAND, recorded.command());
        assertSame(outcome, recorded.outcome());
    }

    private record TestFixture(
            AuthorizationApplicationService service,
            FakeFraudAssessmentPort fraudAssessmentPort,
            FakeNonFraudCheckPort nonFraudCheckPort,
            RecordingAuthorizationLedger ledger) {
    }

    private static final class FakeFraudAssessmentPort implements FraudAssessmentPort {

        private final FraudAssessment result;
        private RuntimeException failure;
        private int invocationCount;
        private AuthorizationCommand lastCommand;

        private FakeFraudAssessmentPort(FraudAssessment result) {
            this.result = result;
        }

        @Override
        public FraudAssessment assess(AuthorizationCommand command) {
            invocationCount++;
            lastCommand = command;
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        private void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        private int invocationCount() {
            return invocationCount;
        }

        private AuthorizationCommand lastCommand() {
            return lastCommand;
        }
    }

    private static final class FakeNonFraudCheckPort implements NonFraudCheckPort {

        private final NonFraudCheckResult result;
        private RuntimeException failure;
        private int invocationCount;
        private AuthorizationCommand lastCommand;

        private FakeNonFraudCheckPort(NonFraudCheckResult result) {
            this.result = result;
        }

        @Override
        public NonFraudCheckResult check(AuthorizationCommand command) {
            invocationCount++;
            lastCommand = command;
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        private void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        private int invocationCount() {
            return invocationCount;
        }

        private AuthorizationCommand lastCommand() {
            return lastCommand;
        }
    }

    private static final class RecordingAuthorizationLedger implements AuthorizationLedgerPort {

        private final List<RecordedAuthorization> records = new ArrayList<>();

        @Override
        public void record(AuthorizationCommand command, AuthorizationOutcome outcome) {
            records.add(new RecordedAuthorization(command, outcome));
        }

        private List<RecordedAuthorization> records() {
            return records;
        }
    }

    private record RecordedAuthorization(
            AuthorizationCommand command,
            AuthorizationOutcome outcome) {
    }
}
