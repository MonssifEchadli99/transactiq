package io.github.monssifechadli99.transactiq.investigation_assistant.domain;

import java.util.List;
import java.util.Objects;

public record ChatGenerationRequest(
        String focalCaseId,
        String question,
        List<GroundingSource> sources) {

    public ChatGenerationRequest {
        Objects.requireNonNull(focalCaseId, "focalCaseId must not be null");
        Objects.requireNonNull(question, "question must not be null");
        sources = List.copyOf(Objects.requireNonNull(sources, "sources must not be null"));
    }

    @Override
    public String toString() {
        return "ChatGenerationRequest[focalCaseId=<redacted>, question=<redacted>, sourceCount="
                + sources.size() + "]";
    }
}
