package io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out;

import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceDraft;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.ValidatedProjection;
import java.util.List;
import java.util.OptionalLong;

/**
 * Out port for the OpenSearch evidence write path. Kept as a dedicated interface
 * so ingestion orchestration can be unit tested with an in-memory fake.
 */
public interface EvidenceIndexPort {
    enum ProjectionAssessment {
        APPLY,
        NO_OP
    }

    OptionalLong currentVersion(String sourceId);

    void index(EvidenceDraft draft, float[] embedding);

    /**
     * Pre-embedding optimization. Production stores must override this method to check
     * both the private integrity discriminator and completeness of the expected chunk set.
     */
    default ProjectionAssessment assessProjection(
            ValidatedProjection projection, List<EvidenceDraft> expectedDrafts) {
        boolean complete = true;
        for (EvidenceDraft draft : expectedDrafts) {
            OptionalLong current = currentVersion(draft.sourceId());
            if (current.isPresent() && current.getAsLong() > draft.projectionVersion()) {
                return ProjectionAssessment.NO_OP;
            }
            if (current.isEmpty() || current.getAsLong() != draft.projectionVersion()) {
                complete = false;
            }
        }
        return complete ? ProjectionAssessment.NO_OP : ProjectionAssessment.APPLY;
    }

    /**
     * Final guarded write. Production stores must independently re-check version,
     * integrity, and expected-chunk completeness rather than trusting the assessment.
     */
    default void indexProjection(
            ValidatedProjection projection,
            List<EvidenceDraft> drafts,
            List<float[]> embeddings) {
        if (drafts.size() != embeddings.size()) {
            throw new IllegalArgumentException("Each evidence draft requires one embedding");
        }
        for (int index = 0; index < drafts.size(); index++) {
            index(drafts.get(index), embeddings.get(index));
        }
    }
}
