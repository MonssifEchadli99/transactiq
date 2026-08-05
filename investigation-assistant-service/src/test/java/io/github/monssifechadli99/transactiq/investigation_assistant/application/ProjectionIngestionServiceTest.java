package io.github.monssifechadli99.transactiq.investigation_assistant.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudCaseProjectionEvent;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EmbeddingDimensionMismatchException;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.ValidatedProjection;
import io.github.monssifechadli99.transactiq.investigation_assistant.support.FakeEmbeddingPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.support.FakeEvidenceIndexPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.support.ProjectionFixtures;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectionIngestionServiceTest {

    private static final int DIMENSIONS = 4;
    private final SafeEvidenceMapper mapper = new SafeEvidenceMapper();
    private final ProjectionValidator validator = new ProjectionValidator();

    @Test
    void newUnresolvedSnapshotEmbedsAndIndexesOneDocument() {
        FakeEmbeddingPort embedding = new FakeEmbeddingPort(DIMENSIONS);
        FakeEvidenceIndexPort index = new FakeEvidenceIndexPort();
        ProjectionIngestionService service = new ProjectionIngestionService(mapper, embedding, index, DIMENSIONS);
        String caseId = UUID.randomUUID().toString();
        FraudCaseProjectionEvent event = ProjectionFixtures.createdEvent(caseId, 0);

        service.ingest(validated(event));

        assertEquals(1, embedding.callCount());
        assertEquals(1, index.documents().size());
        assertTrue(index.documents().containsKey("case:" + caseId + ":evidence"));
    }

    @Test
    void resolvedSnapshotEmbedsAndIndexesBothDocuments() {
        FakeEmbeddingPort embedding = new FakeEmbeddingPort(DIMENSIONS);
        FakeEvidenceIndexPort index = new FakeEvidenceIndexPort();
        ProjectionIngestionService service = new ProjectionIngestionService(mapper, embedding, index, DIMENSIONS);
        String caseId = UUID.randomUUID().toString();
        FraudCaseProjectionEvent event =
                ProjectionFixtures.resolvedEvent(caseId, 3, "CONFIRMED_FRAUD", "synthetic rationale");

        service.ingest(validated(event));

        assertEquals(2, embedding.callCount());
        assertEquals(2, index.documents().size());
    }

    @Test
    void staleVersionIsANoOpAndNeverRequestsAnEmbedding() {
        FakeEmbeddingPort embedding = new FakeEmbeddingPort(DIMENSIONS);
        FakeEvidenceIndexPort index = new FakeEvidenceIndexPort();
        String caseId = UUID.randomUUID().toString();
        index.seedVersion("case:" + caseId + ":evidence", caseId, 5);
        ProjectionIngestionService service = new ProjectionIngestionService(mapper, embedding, index, DIMENSIONS);
        FraudCaseProjectionEvent stale = ProjectionFixtures.createdEvent(caseId, 2);

        service.ingest(validated(stale));

        assertEquals(0, embedding.callCount());
        assertEquals(5, index.documents().get("case:" + caseId + ":evidence").projectionVersion());
    }

    @Test
    void duplicateSameVersionSecondDeliveryIsANoOpWithoutAnotherEmbedding() {
        FakeEmbeddingPort embedding = new FakeEmbeddingPort(DIMENSIONS);
        FakeEvidenceIndexPort index = new FakeEvidenceIndexPort();
        String caseId = UUID.randomUUID().toString();
        ProjectionIngestionService service = new ProjectionIngestionService(mapper, embedding, index, DIMENSIONS);
        FraudCaseProjectionEvent duplicate = ProjectionFixtures.createdEvent(caseId, 0);

        service.ingest(validated(duplicate));
        service.ingest(validated(duplicate));

        assertEquals(1, embedding.callCount());
    }

    @Test
    void partialResolvedWriteIsNotMistakenForACompleteDuplicate() {
        FakeEmbeddingPort embedding = new FakeEmbeddingPort(DIMENSIONS);
        FakeEvidenceIndexPort index = new FakeEvidenceIndexPort();
        String caseId = UUID.randomUUID().toString();
        index.seedVersion("case:" + caseId + ":evidence", caseId, 3);
        ProjectionIngestionService service = new ProjectionIngestionService(mapper, embedding, index, DIMENSIONS);
        FraudCaseProjectionEvent resolved =
                ProjectionFixtures.resolvedEvent(caseId, 3, "CONFIRMED_FRAUD", "synthetic rationale");

        service.ingest(validated(resolved));

        assertEquals(2, embedding.callCount());
        assertEquals(2, index.documents().size());
        assertTrue(index.documents().containsKey("case:" + caseId + ":resolution"));
    }

    @Test
    void incorrectEmbeddingDimensionIsRejectedAndNeverIndexed() {
        FakeEmbeddingPort wrongDimensionEmbedding = new FakeEmbeddingPort(3);
        FakeEvidenceIndexPort index = new FakeEvidenceIndexPort();
        ProjectionIngestionService service =
                new ProjectionIngestionService(mapper, wrongDimensionEmbedding, index, DIMENSIONS);
        FraudCaseProjectionEvent event =
                ProjectionFixtures.createdEvent(UUID.randomUUID().toString(), 0);

        assertThrows(EmbeddingDimensionMismatchException.class, () -> service.ingest(validated(event)));
        assertTrue(index.documents().isEmpty());
    }

    private ValidatedProjection validated(FraudCaseProjectionEvent event) {
        return validator.validateProjection(ProjectionFixtures.keyOf(event), event);
    }
}
