package io.github.monssifechadli99.transactiq.investigation_assistant.domain;

import java.util.List;

public record InvestigationRetrievalResult(
        String focalCaseId,
        List<RetrievedSource> focalSources,
        List<RelatedCaseGroup> relatedCases) {
    public InvestigationRetrievalResult {
        focalSources = List.copyOf(focalSources);
        relatedCases = List.copyOf(relatedCases);
    }
}
