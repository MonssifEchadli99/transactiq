package io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out;

import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceHit;
import java.util.List;

/**
 * Out port for the OpenSearch read path used by investigation retrieval.
 */
public interface EvidenceRetrievalPort {
    List<EvidenceHit> loadFocal(String caseId);

    List<EvidenceHit> hybridSearch(
            String excludeCaseId, String retrievalText, float[] retrievalEmbedding, int candidatePoolSize);
}
