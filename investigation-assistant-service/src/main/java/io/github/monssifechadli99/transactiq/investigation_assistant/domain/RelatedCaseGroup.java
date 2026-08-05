package io.github.monssifechadli99.transactiq.investigation_assistant.domain;

import java.util.List;

public record RelatedCaseGroup(String caseId, List<RetrievedSource> sources) {
    public RelatedCaseGroup {
        sources = List.copyOf(sources);
    }
}
