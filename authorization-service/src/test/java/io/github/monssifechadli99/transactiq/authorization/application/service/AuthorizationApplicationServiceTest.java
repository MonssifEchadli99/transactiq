package io.github.monssifechadli99.transactiq.authorization.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationChannel;
import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationProcessingResult;
import io.github.monssifechadli99.transactiq.authorization.application.model.IdempotencyClaimResult;
import io.github.monssifechadli99.transactiq.authorization.application.model.PreAuthorizationRejectionException;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.AuthorizationLedgerPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.FraudAssessmentPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.IdempotencyClaimPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.NonFraudCheckPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.TransactionExecutorPort;
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
import java.util.function.Supplier;
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
    void claimedRequestRunsFraudAndAtomicCompletion() {
        TestFixture fixture = fixture(
                new IdempotencyClaimResult.Claimed(),
                FraudAssessment.CLEAR,
                NonFraudCheckResult.PASSED);

        AuthorizationProcessingResult.Completed result = assertInstanceOf(
                AuthorizationProcessingResult.Completed.class,
                fixture.service().authorize(COMMAND));

        assertInstanceOf(AuthorizationOutcome.Approved.class, result.outcome());
        assertEquals(1, fixture.idempotency().claimCount());
        assertEquals(0, fixture.idempotency().releaseCount());
        assertEquals(1, fixture.fraud().invocationCount());
        assertEquals(1, fixture.nonFraud().invocationCount());
        assertEquals(1, fixture.transactionExecutor().invocationCount());
        assertEquals(1, fixture.ledger().records().size());
    }

    @Test
    void completedClaimReturnsStoredOutcomeWithoutRunningWorkflow() {
        AuthorizationOutcome stored = new AuthorizationOutcome.Declined(
                DeclineReason.FRAUD_REVIEW_REQUIRED, true);
        TestFixture fixture = fixture(
                new IdempotencyClaimResult.Completed(stored),
                FraudAssessment.CLEAR,
                NonFraudCheckResult.PASSED);

        AuthorizationProcessingResult.Completed result = assertInstanceOf(
                AuthorizationProcessingResult.Completed.class,
                fixture.service().authorize(COMMAND));

        assertSame(stored, result.outcome());
        assertWorkflowNotRun(fixture);
    }

    @Test
    void pendingClaimReturnsExpectedWorkflowStateWithoutRunningWorkflow() {
        TestFixture fixture = fixture(
                new IdempotencyClaimResult.Pending(),
                FraudAssessment.CLEAR,
                NonFraudCheckResult.PASSED);

        AuthorizationProcessingResult.Pending result = assertInstanceOf(
                AuthorizationProcessingResult.Pending.class,
                fixture.service().authorize(COMMAND));

        assertEquals(COMMAND.requestId(), result.requestId());
        assertWorkflowNotRun(fixture);
    }

    @Test
    void conflictingClaimReturnsExpectedWorkflowStateWithoutRunningWorkflow() {
        TestFixture fixture = fixture(
                new IdempotencyClaimResult.Conflict(),
                FraudAssessment.CLEAR,
                NonFraudCheckResult.PASSED);

        AuthorizationProcessingResult.Conflict result = assertInstanceOf(
                AuthorizationProcessingResult.Conflict.class,
                fixture.service().authorize(COMMAND));

        assertEquals(COMMAND.requestId(), result.requestId());
        assertWorkflowNotRun(fixture);
    }

    @Test
    void preAuthorizationRejectionReleasesPendingClaimAndPropagatesOriginalFailure() {
        TestFixture fixture = fixture(
                new IdempotencyClaimResult.Claimed(),
                FraudAssessment.CLEAR,
                NonFraudCheckResult.PASSED);
        PreAuthorizationRejectionException failure = new PreAuthorizationRejectionException(
                PreAuthorizationRejectionException.Reason.UNKNOWN_CARD_TOKEN);
        fixture.nonFraud().failWith(failure);

        PreAuthorizationRejectionException thrown = assertThrows(
                PreAuthorizationRejectionException.class,
                () -> fixture.service().authorize(COMMAND));

        assertSame(failure, thrown);
        assertEquals(1, fixture.fraud().invocationCount());
        assertEquals(1, fixture.nonFraud().invocationCount());
        assertEquals(1, fixture.idempotency().releaseCount());
        assertEquals(COMMAND.requestId(), fixture.idempotency().releasedRequestIds().getFirst());
        assertTrue(fixture.ledger().records().isEmpty());
    }

    @Test
    void technicalFraudFailureReleasesPendingClaimAndPropagatesOriginalFailure() {
        TestFixture fixture = fixture(
                new IdempotencyClaimResult.Claimed(),
                FraudAssessment.CLEAR,
                NonFraudCheckResult.PASSED);
        IllegalStateException failure = new IllegalStateException("fraud unavailable");
        fixture.fraud().failWith(failure);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> fixture.service().authorize(COMMAND));

        assertSame(failure, thrown);
        assertEquals(1, fixture.idempotency().releaseCount());
        assertEquals(0, fixture.nonFraud().invocationCount());
        assertEquals(0, fixture.transactionExecutor().invocationCount());
        assertTrue(fixture.ledger().records().isEmpty());
    }

    @Test
    void releaseFailureIsSuppressedOnOriginalProcessingFailure() {
        TestFixture fixture = fixture(
                new IdempotencyClaimResult.Claimed(),
                FraudAssessment.CLEAR,
                NonFraudCheckResult.PASSED);
        IllegalStateException processingFailure = new IllegalStateException("fraud unavailable");
        IllegalStateException releaseFailure = new IllegalStateException("release unavailable");
        fixture.fraud().failWith(processingFailure);
        fixture.idempotency().failReleaseWith(releaseFailure);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> fixture.service().authorize(COMMAND));

        assertSame(processingFailure, thrown);
        assertEquals(List.of(releaseFailure), List.of(thrown.getSuppressed()));
    }

    private static TestFixture fixture(
            IdempotencyClaimResult claimResult,
            FraudAssessment fraudAssessment,
            NonFraudCheckResult nonFraudCheckResult) {
        FakeIdempotencyClaimPort idempotency = new FakeIdempotencyClaimPort(claimResult);
        FakeFraudAssessmentPort fraud = new FakeFraudAssessmentPort(fraudAssessment);
        FakeNonFraudCheckPort nonFraud = new FakeNonFraudCheckPort(nonFraudCheckResult);
        RecordingAuthorizationLedger ledger = new RecordingAuthorizationLedger();
        SameThreadTransactionExecutor transactionExecutor = new SameThreadTransactionExecutor();
        AuthorizationCompletionService completionService = new AuthorizationCompletionService(
                transactionExecutor,
                nonFraud,
                ledger,
                new AuthorizationPolicy());
        AuthorizationApplicationService service = new AuthorizationApplicationService(
                idempotency, fraud, completionService);
        return new TestFixture(
                service, idempotency, fraud, nonFraud, ledger, transactionExecutor);
    }

    private static void assertWorkflowNotRun(TestFixture fixture) {
        assertEquals(0, fixture.fraud().invocationCount());
        assertEquals(0, fixture.nonFraud().invocationCount());
        assertEquals(0, fixture.transactionExecutor().invocationCount());
        assertTrue(fixture.ledger().records().isEmpty());
        assertEquals(0, fixture.idempotency().releaseCount());
    }

    private record TestFixture(
            AuthorizationApplicationService service,
            FakeIdempotencyClaimPort idempotency,
            FakeFraudAssessmentPort fraud,
            FakeNonFraudCheckPort nonFraud,
            RecordingAuthorizationLedger ledger,
            SameThreadTransactionExecutor transactionExecutor) {}

    private static final class FakeIdempotencyClaimPort implements IdempotencyClaimPort {

        private final IdempotencyClaimResult claimResult;
        private final List<UUID> releasedRequestIds = new ArrayList<>();
        private RuntimeException releaseFailure;
        private int claimCount;

        private FakeIdempotencyClaimPort(IdempotencyClaimResult claimResult) {
            this.claimResult = claimResult;
        }

        @Override
        public IdempotencyClaimResult claim(AuthorizationCommand command) {
            claimCount++;
            return claimResult;
        }

        @Override
        public boolean releasePending(UUID requestId) {
            releasedRequestIds.add(requestId);
            if (releaseFailure != null) {
                throw releaseFailure;
            }
            return true;
        }

        private void failReleaseWith(RuntimeException failure) {
            releaseFailure = failure;
        }

        private int claimCount() {
            return claimCount;
        }

        private int releaseCount() {
            return releasedRequestIds.size();
        }

        private List<UUID> releasedRequestIds() {
            return List.copyOf(releasedRequestIds);
        }
    }

    private static final class FakeFraudAssessmentPort implements FraudAssessmentPort {

        private final FraudAssessment result;
        private RuntimeException failure;
        private int invocationCount;

        private FakeFraudAssessmentPort(FraudAssessment result) {
            this.result = result;
        }

        @Override
        public FraudAssessment assess(AuthorizationCommand command) {
            invocationCount++;
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
    }

    private static final class FakeNonFraudCheckPort implements NonFraudCheckPort {

        private final NonFraudCheckResult result;
        private RuntimeException failure;
        private int invocationCount;

        private FakeNonFraudCheckPort(NonFraudCheckResult result) {
            this.result = result;
        }

        @Override
        public NonFraudCheckResult check(AuthorizationCommand command) {
            invocationCount++;
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
    }

    private static final class RecordingAuthorizationLedger implements AuthorizationLedgerPort {

        private final List<AuthorizationOutcome> records = new ArrayList<>();

        @Override
        public void record(
                AuthorizationCommand command,
                FraudAssessment fraudAssessment,
                NonFraudCheckResult nonFraudCheckResult,
                AuthorizationOutcome outcome) {
            records.add(outcome);
        }

        private List<AuthorizationOutcome> records() {
            return List.copyOf(records);
        }
    }

    private static final class SameThreadTransactionExecutor implements TransactionExecutorPort {

        private int invocationCount;

        @Override
        public <T> T execute(Supplier<T> operation) {
            invocationCount++;
            return operation.get();
        }

        private int invocationCount() {
            return invocationCount;
        }
    }
}
