package io.github.monssifechadli99.transactiq.investigation_assistant.domain;

public record EvidenceDraft(
        String sourceId,
        EvidenceSourceType sourceType,
        String caseId,
        String text,
        long projectionVersion) {
}
