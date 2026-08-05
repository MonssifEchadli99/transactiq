package io.github.monssifechadli99.transactiq.investigation_assistant.domain;

public record RetrievedSource(
        String sourceId,
        EvidenceSourceType sourceType,
        String caseId,
        String excerpt) {
}
