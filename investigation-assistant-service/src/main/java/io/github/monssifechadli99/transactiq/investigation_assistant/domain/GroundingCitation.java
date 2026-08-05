package io.github.monssifechadli99.transactiq.investigation_assistant.domain;

/** Public metadata for one source that grounds a generated finding. */
public record GroundingCitation(
        String sourceId,
        EvidenceSourceType sourceType,
        String caseId,
        String excerpt) {
    @Override
    public String toString() {
        return "GroundingCitation[content=<redacted>]";
    }
}
