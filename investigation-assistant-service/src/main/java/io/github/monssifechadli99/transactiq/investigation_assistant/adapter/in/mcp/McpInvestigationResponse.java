package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.mcp;

import java.util.List;

/** Safe public MCP response shapes. Content-bearing diagnostic representations are redacted. */
public final class McpInvestigationResponse {

    private McpInvestigationResponse() {}

    public record Retrieval(String caseId, List<Source> focalSources, List<RelatedCase> relatedCases) {
        public Retrieval {
            focalSources = List.copyOf(focalSources);
            relatedCases = List.copyOf(relatedCases);
        }

        @Override
        public String toString() {
            return "McpInvestigationResponse.Retrieval[content=<redacted>]";
        }
    }

    public record Source(String sourceId, String sourceType, String caseId, String excerpt) {
        @Override
        public String toString() {
            return "McpInvestigationResponse.Source[content=<redacted>]";
        }
    }

    public record RelatedCase(String caseId, List<Source> sources) {
        public RelatedCase {
            sources = List.copyOf(sources);
        }

        @Override
        public String toString() {
            return "McpInvestigationResponse.RelatedCase[content=<redacted>]";
        }
    }

    public record Answer(
            String caseId,
            String summary,
            List<Finding> findings,
            List<String> recommendedChecks,
            String groundingStatus) {
        public Answer {
            findings = List.copyOf(findings);
            recommendedChecks = List.copyOf(recommendedChecks);
        }

        @Override
        public String toString() {
            return "McpInvestigationResponse.Answer[content=<redacted>, findingCount="
                    + findings.size() + ", recommendedCheckCount=" + recommendedChecks.size() + "]";
        }
    }

    public record Finding(String text, List<Citation> citations) {
        public Finding {
            citations = List.copyOf(citations);
        }

        @Override
        public String toString() {
            return "McpInvestigationResponse.Finding[content=<redacted>, citationCount="
                    + citations.size() + "]";
        }
    }

    public record Citation(String sourceId, String sourceType, String caseId, String excerpt) {
        @Override
        public String toString() {
            return "McpInvestigationResponse.Citation[content=<redacted>]";
        }
    }
}
