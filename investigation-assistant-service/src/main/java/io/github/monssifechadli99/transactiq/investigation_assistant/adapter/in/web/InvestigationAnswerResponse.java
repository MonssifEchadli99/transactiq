package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web;

import java.util.List;

public final class InvestigationAnswerResponse {
    private InvestigationAnswerResponse() {}

    public record Response(
            String caseId,
            String summary,
            List<Finding> findings,
            List<String> recommendedChecks,
            String groundingStatus) {
        public Response {
            findings = List.copyOf(findings);
            recommendedChecks = List.copyOf(recommendedChecks);
        }

        @Override
        public String toString() {
            return "InvestigationAnswerResponse.Response[content=<redacted>, findingCount="
                    + findings.size() + ", recommendedCheckCount=" + recommendedChecks.size() + "]";
        }
    }

    public record Finding(String text, List<Citation> citations) {
        public Finding {
            citations = List.copyOf(citations);
        }

        @Override
        public String toString() {
            return "InvestigationAnswerResponse.Finding[content=<redacted>, citationCount="
                    + citations.size() + "]";
        }
    }

    public record Citation(String sourceId, String sourceType, String caseId, String excerpt) {
        @Override
        public String toString() {
            return "InvestigationAnswerResponse.Citation[content=<redacted>]";
        }
    }
}
