package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web;

public record InvestigationRetrievalRequest(
        String question,
        Integer maxRelatedCases) {

    public InvestigationRetrievalRequest {
        question = question == null ? null : question.strip();
    }

    @Override
    public String toString() {
        return "InvestigationRetrievalRequest[question=<redacted>, maxRelatedCases="
                + maxRelatedCases + "]";
    }
}
