package io.github.monssifechadli99.transactiq.investigation_assistant.domain;

import java.util.List;

/** A generated factual finding whose citations were resolved against retrieved evidence. */
public record GroundedFinding(String text, List<GroundingCitation> citations) {
    public GroundedFinding {
        citations = List.copyOf(citations);
    }

    @Override
    public String toString() {
        return "GroundedFinding[content=<redacted>, citationCount=" + citations.size() + "]";
    }
}
