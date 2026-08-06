package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.out.demo;

import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.ChatGenerationPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.ChatGenerationRequest;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GeneratedAnswerDraft;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GeneratedFindingDraft;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GroundingSource;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GroundingStatus;
import java.util.List;
import java.util.Objects;

/**
 * Fixed advisory generation used only by the explicit demo profile. The answer cites a
 * source selected from the retrieval allow-list, so normal server-side grounding validation
 * remains in force.
 */
public final class DemoOfflineChatGenerationAdapter implements ChatGenerationPort {

    @Override
    public GeneratedAnswerDraft generate(ChatGenerationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.sources().isEmpty()) {
            return new GeneratedAnswerDraft(
                    "The retrieved evidence is insufficient for an advisory finding.",
                    List.of(),
                    List.of("Collect another published synthetic evidence snapshot."),
                    GroundingStatus.INSUFFICIENT_EVIDENCE);
        }

        GroundingSource focal = request.sources().stream()
                .filter(source -> source.caseId().equals(request.focalCaseId()))
                .findFirst()
                .orElse(request.sources().getFirst());
        return new GeneratedAnswerDraft(
                "Published synthetic evidence supports continued analyst review.",
                List.of(new GeneratedFindingDraft(
                        "The focal case has published evidence available for advisory review.",
                        List.of(focal.sourceId()))),
                List.of(
                        "Review the cited case evidence and transaction context.",
                        "Confirm the final disposition independently before resolving the case."),
                GroundingStatus.GROUNDED);
    }
}
