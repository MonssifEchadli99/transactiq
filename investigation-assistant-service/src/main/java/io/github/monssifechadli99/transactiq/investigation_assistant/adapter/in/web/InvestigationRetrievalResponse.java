package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web;

import java.util.List;

public final class InvestigationRetrievalResponse {
    private InvestigationRetrievalResponse() {}

    public record Response(String caseId, List<Source> focalSources, List<RelatedCase> relatedCases) {
        public Response {
            focalSources = List.copyOf(focalSources);
            relatedCases = List.copyOf(relatedCases);
        }

        @Override
        public String toString() {
            return "InvestigationRetrievalResponse.Response[content=<redacted>]";
        }
    }

    public record RelatedCase(String caseId, List<Source> sources) {
        public RelatedCase {
            sources = List.copyOf(sources);
        }

        @Override
        public String toString() {
            return "InvestigationRetrievalResponse.RelatedCase[content=<redacted>]";
        }
    }

    public record Source(String sourceId, String sourceType, String caseId, String excerpt) {
        @Override
        public String toString() {
            return "InvestigationRetrievalResponse.Source[content=<redacted>]";
        }
    }
}
