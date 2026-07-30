package io.github.monssifechadli99.transactiq.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.monssifechadli99.transactiq.authorization.adapter.out.memory.DeterministicFraudAssessmentAdapter;
import io.github.monssifechadli99.transactiq.authorization.adapter.out.memory.DeterministicNonFraudCheckAdapter;
import io.github.monssifechadli99.transactiq.authorization.adapter.out.memory.InMemoryAuthorizationLedgerAdapter;
import io.github.monssifechadli99.transactiq.authorization.adapter.out.memory.InMemoryAuthorizationLedgerAdapter.LedgerEntry;
import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationChannel;
import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationProcessingResult;
import io.github.monssifechadli99.transactiq.authorization.application.model.IdempotencyClaimResult;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.IdempotencyClaimPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.TransactionExecutorPort;
import io.github.monssifechadli99.transactiq.authorization.application.service.AuthorizationApplicationService;
import io.github.monssifechadli99.transactiq.authorization.application.service.AuthorizationCompletionService;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationOutcome;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationPolicy;
import io.github.monssifechadli99.transactiq.authorization.domain.NonFraudCheckResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class AuthorizationInMemoryIntegrationTest {

    @Test
    void orchestratesReviewOutcomeWithFocusedInMemoryAdapters() {
        InMemoryAuthorizationLedgerAdapter ledger = new InMemoryAuthorizationLedgerAdapter();
        AuthorizationCompletionService completionService = new AuthorizationCompletionService(
                new SameThreadTransactionExecutor(),
                new DeterministicNonFraudCheckAdapter(),
                ledger,
                (command, fraudAssessment, nonFraudCheckResult, outcome) -> {},
                new AuthorizationPolicy());
        AuthorizationApplicationService service = new AuthorizationApplicationService(
                new AlwaysClaimedIdempotencyPort(),
                new DeterministicFraudAssessmentAdapter(),
                completionService);
        AuthorizationCommand command = new AuthorizationCommand(
                UUID.fromString("14f9943b-da16-41e2-8f28-bd491a109e49"),
                "tok_A1B2C3D4",
                "merchant-review",
                "5411",
                new BigDecimal("42.50"),
                "EUR",
                "DE",
                AuthorizationChannel.ECOMMERCE,
                Instant.parse("2026-07-19T10:15:30Z"));

        AuthorizationProcessingResult.Completed result = assertInstanceOf(
                AuthorizationProcessingResult.Completed.class,
                service.authorize(command));

        assertInstanceOf(AuthorizationOutcome.Approved.class, result.outcome());
        assertEquals(
                new LedgerEntry(
                        command,
                        result.fraudAssessment(),
                        NonFraudCheckResult.PASSED,
                        result.outcome()),
                ledger.snapshot().getFirst());
    }

    private static final class AlwaysClaimedIdempotencyPort implements IdempotencyClaimPort {

        @Override
        public IdempotencyClaimResult claim(AuthorizationCommand command) {
            return new IdempotencyClaimResult.Claimed();
        }

        @Override
        public boolean releasePending(UUID requestId) {
            return true;
        }
    }

    private static final class SameThreadTransactionExecutor implements TransactionExecutorPort {

        @Override
        public <T> T execute(Supplier<T> operation) {
            return operation.get();
        }
    }
}
