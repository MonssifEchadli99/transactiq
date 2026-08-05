package io.github.monssifechadli99.transactiq.investigation_assistant.support;

import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EvidenceIndexPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceDraft;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceSourceType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;

/** In-memory {@link EvidenceIndexPort} fake for unit-testing ingestion orchestration. */
public final class FakeEvidenceIndexPort implements EvidenceIndexPort {

    private final Map<String, EvidenceDraft> documents = new LinkedHashMap<>();

    @Override
    public OptionalLong currentVersion(String sourceId) {
        EvidenceDraft existing = documents.get(sourceId);
        return existing == null ? OptionalLong.empty() : OptionalLong.of(existing.projectionVersion());
    }

    @Override
    public void index(EvidenceDraft draft, float[] embedding) {
        documents.put(draft.sourceId(), draft);
    }

    public void seedVersion(String sourceId, String caseId, long version) {
        documents.put(sourceId, new EvidenceDraft(sourceId, EvidenceSourceType.CASE_EVIDENCE, caseId, "", version));
    }

    public Map<String, EvidenceDraft> documents() {
        return Map.copyOf(documents);
    }
}
