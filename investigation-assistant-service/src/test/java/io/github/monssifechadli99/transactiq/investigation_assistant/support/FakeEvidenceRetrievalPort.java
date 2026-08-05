package io.github.monssifechadli99.transactiq.investigation_assistant.support;

import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EvidenceRetrievalPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceHit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory {@link EvidenceRetrievalPort} fake for unit-testing retrieval orchestration. */
public final class FakeEvidenceRetrievalPort implements EvidenceRetrievalPort {

    private final Map<String, List<EvidenceHit>> focalByCase = new HashMap<>();
    private List<EvidenceHit> hybridResults = List.of();
    private String lastExcludeCaseId;

    public void seedFocal(String caseId, List<EvidenceHit> hits) {
        focalByCase.put(caseId, hits);
    }

    public void seedHybridResults(List<EvidenceHit> hits) {
        hybridResults = hits;
    }

    public String lastExcludeCaseId() {
        return lastExcludeCaseId;
    }

    @Override
    public List<EvidenceHit> loadFocal(String caseId) {
        return focalByCase.getOrDefault(caseId, List.of());
    }

    @Override
    public List<EvidenceHit> hybridSearch(
            String excludeCaseId, String retrievalText, float[] retrievalEmbedding, int candidatePoolSize) {
        this.lastExcludeCaseId = excludeCaseId;
        return hybridResults;
    }
}
