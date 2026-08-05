package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web;

public record InvestigationAnswerRequest(String question) {
    public InvestigationAnswerRequest {
        question = question == null ? null : question.strip();
    }

    @Override
    public String toString() {
        return "InvestigationAnswerRequest[question=<redacted>]";
    }
}
