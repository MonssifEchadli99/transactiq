package io.github.monssifechadli99.transactiq.investigation_assistant.domain;

import java.util.List;
import java.util.Objects;

/** Untrusted provider output. Citation validity is enforced by the application service. */
public record GeneratedFindingDraft(String text, List<String> citationIds) {

    public GeneratedFindingDraft {
        Objects.requireNonNull(text, "text must not be null");
        citationIds = List.copyOf(Objects.requireNonNull(citationIds, "citationIds must not be null"));
    }

    @Override
    public String toString() {
        return "GeneratedFindingDraft[text=<redacted>, citationCount=" + citationIds.size() + "]";
    }
}
