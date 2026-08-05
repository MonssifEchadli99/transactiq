package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web;

import io.github.monssifechadli99.transactiq.investigation_assistant.domain.InvestigationRetrievalResult;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.RelatedCaseGroup;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.RetrievedSource;

public final class InvestigationApiMapper {

    public InvestigationRetrievalResponse.Response toResponse(InvestigationRetrievalResult result) {
        return new InvestigationRetrievalResponse.Response(
                result.focalCaseId(),
                result.focalSources().stream().map(this::toSource).toList(),
                result.relatedCases().stream().map(this::toRelatedCase).toList());
    }

    private InvestigationRetrievalResponse.RelatedCase toRelatedCase(RelatedCaseGroup group) {
        return new InvestigationRetrievalResponse.RelatedCase(
                group.caseId(), group.sources().stream().map(this::toSource).toList());
    }

    private InvestigationRetrievalResponse.Source toSource(RetrievedSource source) {
        return new InvestigationRetrievalResponse.Source(
                source.sourceId(), source.sourceType().name(), source.caseId(), source.excerpt());
    }
}
