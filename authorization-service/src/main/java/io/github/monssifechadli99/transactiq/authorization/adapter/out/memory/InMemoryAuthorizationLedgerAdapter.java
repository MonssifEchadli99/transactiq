package io.github.monssifechadli99.transactiq.authorization.adapter.out.memory;

import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.AuthorizationLedgerPort;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationOutcome;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessmentResult;
import io.github.monssifechadli99.transactiq.authorization.domain.NonFraudCheckResult;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class InMemoryAuthorizationLedgerAdapter implements AuthorizationLedgerPort {

    private final Queue<LedgerEntry> entries = new ConcurrentLinkedQueue<>();

    @Override
    public void record(
            AuthorizationCommand command,
            FraudAssessmentResult fraudAssessment,
            NonFraudCheckResult nonFraudCheckResult,
            AuthorizationOutcome outcome) {
        entries.add(new LedgerEntry(
                command, fraudAssessment, nonFraudCheckResult, outcome));
    }

    public List<LedgerEntry> snapshot() {
        return List.copyOf(entries);
    }

    public record LedgerEntry(
            AuthorizationCommand command,
            FraudAssessmentResult fraudAssessment,
            NonFraudCheckResult nonFraudCheckResult,
            AuthorizationOutcome outcome) {

        public LedgerEntry {
            Objects.requireNonNull(command, "command must not be null");
            Objects.requireNonNull(fraudAssessment, "fraudAssessment must not be null");
            Objects.requireNonNull(
                    nonFraudCheckResult, "nonFraudCheckResult must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
        }
    }
}
