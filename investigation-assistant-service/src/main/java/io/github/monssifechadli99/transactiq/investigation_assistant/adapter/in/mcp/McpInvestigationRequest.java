package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.mcp;

/** A short-lived MCP input value whose diagnostic representation never retains the question. */
public record McpInvestigationRequest(String caseId, String question) {

    public McpInvestigationRequest {
        question = question == null ? null : question.strip();
    }

    @Override
    public String toString() {
        return "McpInvestigationRequest[caseId=<redacted>, question=<redacted>]";
    }
}
