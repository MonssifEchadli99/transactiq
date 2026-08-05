package io.github.monssifechadli99.transactiq.investigation_assistant.domain;

import java.util.Objects;

/** A public evidence source supplied to generation as untrusted data. */
public record GroundingSource(
        String sourceId,
        EvidenceSourceType sourceType,
        String caseId,
        String text) {

    public GroundingSource {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(text, "text must not be null");
    }

    @Override
    public String toString() {
        return "GroundingSource[sourceId=<redacted>, sourceType=" + sourceType
                + ", caseId=<redacted>, text=<redacted>]";
    }
}
