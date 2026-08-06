package io.github.monssifechadli99.transactiq.authorization.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.monssifechadli99.transactiq.authorization.api.AuthorizationRequest;
import io.github.monssifechadli99.transactiq.authorization.api.AuthorizationResponse;
import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationProcessingResult;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizeTransactionUseCase;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationDecision;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationOutcome;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessmentResult;
import io.github.monssifechadli99.transactiq.observability.PortfolioMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthorizationControllerMetricsTest {

    @Test
    void recordsOnlyTheBoundedCompletedDecision() {
        UUID requestId = UUID.fromString("08ebd62f-e57a-4e68-8c99-a1849ccf1720");
        AuthorizationRequest request = mock(AuthorizationRequest.class);
        AuthorizationCommand command = mock(AuthorizationCommand.class);
        AuthorizeTransactionUseCase useCase = mock(AuthorizeTransactionUseCase.class);
        AuthorizationHttpMapper mapper = mock(AuthorizationHttpMapper.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuthorizationOutcome outcome = new AuthorizationOutcome.Approved(false);
        when(mapper.toCommand(request)).thenReturn(command);
        when(useCase.authorize(command)).thenReturn(
                new AuthorizationProcessingResult.Completed(outcome, FraudAssessmentResult.clear()));
        when(mapper.toResponse(command, outcome))
                .thenReturn(new AuthorizationResponse.Approved(requestId, AuthorizationDecision.APPROVED));
        AuthorizationController controller =
                new AuthorizationController(useCase, mapper, new PortfolioMetrics(registry));

        controller.authorize(request);

        assertThat(registry.get("transactiq.authorization.processed")
                        .tags("result", "completed", "decision", "approved")
                        .counter()
                        .count())
                .isEqualTo(1);
    }
}
