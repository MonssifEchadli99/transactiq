package io.github.monssifechadli99.transactiq.investigation_assistant.domain;

public record EvidenceHit(
        String sourceId,
        EvidenceSourceType sourceType,
        String caseId,
        String text) {
}
