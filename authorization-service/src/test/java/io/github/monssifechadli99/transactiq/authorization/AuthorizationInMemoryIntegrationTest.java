package io.github.monssifechadli99.transactiq.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.monssifechadli99.transactiq.authorization.adapter.out.memory.InMemoryAuthorizationLedgerAdapter;
import io.github.monssifechadli99.transactiq.authorization.adapter.out.memory.InMemoryAuthorizationLedgerAdapter.LedgerEntry;
import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationChannel;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizeTransactionUseCase;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationOutcome;
import io.github.monssifechadli99.transactiq.authorization.domain.DeclineReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AuthorizationInMemoryIntegrationTest {

    @Autowired
    private AuthorizeTransactionUseCase authorizeTransactionUseCase;

    @Autowired
    private InMemoryAuthorizationLedgerAdapter ledgerAdapter;

    @Test
    void orchestratesReviewOutcomeWithRealInMemoryAdapters() {
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
        long entriesBefore = entriesFor(command.requestId()).size();

        AuthorizationOutcome outcome = authorizeTransactionUseCase.authorize(command);

        AuthorizationOutcome.Declined declined =
                assertInstanceOf(AuthorizationOutcome.Declined.class, outcome);
        assertEquals(DeclineReason.FRAUD_REVIEW_REQUIRED, declined.declineReason());
        List<LedgerEntry> matchingEntries = entriesFor(command.requestId());
        assertEquals(entriesBefore + 1, matchingEntries.size());
        assertEquals(new LedgerEntry(command, outcome), matchingEntries.getLast());
    }

    private List<LedgerEntry> entriesFor(UUID requestId) {
        return ledgerAdapter.snapshot().stream()
                .filter(entry -> entry.command().requestId().equals(requestId))
                .toList();
    }
}
