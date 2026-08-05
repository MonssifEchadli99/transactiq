package io.github.monssifechadli99.transactiq.investigation_assistant.application;

import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EmbeddingPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EvidenceIndexPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EmbeddingDimensionMismatchException;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceDraft;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.ValidatedProjection;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a validated projection into indexed evidence. A complete duplicate or stale
 * projection is skipped before spending an embedding call; a partial equal-version write
 * remains repairable. The final store call independently enforces the same rules.
 */
public final class ProjectionIngestionService {

    private final SafeEvidenceMapper mapper;
    private final EmbeddingPort embeddingPort;
    private final EvidenceIndexPort indexPort;
    private final int expectedDimensions;

    public ProjectionIngestionService(
            SafeEvidenceMapper mapper,
            EmbeddingPort embeddingPort,
            EvidenceIndexPort indexPort,
            int expectedDimensions) {
        this.mapper = mapper;
        this.embeddingPort = embeddingPort;
        this.indexPort = indexPort;
        this.expectedDimensions = expectedDimensions;
    }

    public void ingest(ValidatedProjection projection) {
        List<EvidenceDraft> drafts = mapper.map(projection.snapshot());
        if (indexPort.assessProjection(projection, drafts)
                == EvidenceIndexPort.ProjectionAssessment.NO_OP) {
            return;
        }

        List<float[]> embeddings = new ArrayList<>(drafts.size());
        for (EvidenceDraft draft : drafts) {
            float[] embedding = embeddingPort.embed(draft.text());
            if (embedding.length != expectedDimensions) {
                throw new EmbeddingDimensionMismatchException(
                        "Expected " + expectedDimensions + "-dimension embedding but received "
                                + embedding.length + " for " + draft.sourceId());
            }
            embeddings.add(embedding);
        }
        indexPort.indexProjection(projection, drafts, List.copyOf(embeddings));
    }
}
