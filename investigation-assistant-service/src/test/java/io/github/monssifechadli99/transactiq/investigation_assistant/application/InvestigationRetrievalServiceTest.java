package io.github.monssifechadli99.transactiq.investigation_assistant.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceHit;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceSourceType;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.FocalEvidenceNotFoundException;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.InvestigationRetrievalResult;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.RelatedCaseGroup;
import io.github.monssifechadli99.transactiq.investigation_assistant.support.FakeEmbeddingPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.support.FakeEvidenceRetrievalPort;
import java.util.List;
import org.junit.jupiter.api.Test;

class InvestigationRetrievalServiceTest {

    private static final int DIMENSIONS = 4;

    private InvestigationRetrievalService service(FakeEvidenceRetrievalPort retrievalPort) {
        return new InvestigationRetrievalService(retrievalPort, new FakeEmbeddingPort(DIMENSIONS), 50, 2000, 500);
    }

    @Test
    void missingFocalEvidenceIsReportedAsNotFound() {
        FakeEvidenceRetrievalPort retrievalPort = new FakeEvidenceRetrievalPort();

        assertThrows(FocalEvidenceNotFoundException.class,
                () -> service(retrievalPort).retrieve("case-1", "why?", 5));
    }

    @Test
    void focalSourcesAreReturnedAndFocalCaseIsExcludedFromSearch() {
        FakeEvidenceRetrievalPort retrievalPort = new FakeEvidenceRetrievalPort();
        retrievalPort.seedFocal("case-1", List.of(
                new EvidenceHit("case:case-1:evidence", EvidenceSourceType.CASE_EVIDENCE, "case-1", "focal evidence")));

        InvestigationRetrievalResult result = service(retrievalPort).retrieve("case-1", "why?", 5);

        assertEquals("case-1", result.focalCaseId());
        assertEquals(1, result.focalSources().size());
        assertEquals("case-1", retrievalPort.lastExcludeCaseId());
    }

    @Test
    void relatedCasesAreGroupedAndCappedAtMaxRelatedCases() {
        FakeEvidenceRetrievalPort retrievalPort = new FakeEvidenceRetrievalPort();
        retrievalPort.seedFocal("case-1", List.of(
                new EvidenceHit("case:case-1:evidence", EvidenceSourceType.CASE_EVIDENCE, "case-1", "focal")));
        retrievalPort.seedHybridResults(List.of(
                new EvidenceHit("case:case-2:evidence", EvidenceSourceType.CASE_EVIDENCE, "case-2", "a"),
                new EvidenceHit("case:case-2:resolution", EvidenceSourceType.RESOLUTION, "case-2", "b"),
                new EvidenceHit("case:case-3:evidence", EvidenceSourceType.CASE_EVIDENCE, "case-3", "c"),
                new EvidenceHit("case:case-4:evidence", EvidenceSourceType.CASE_EVIDENCE, "case-4", "d")));

        InvestigationRetrievalResult result = service(retrievalPort).retrieve("case-1", "why?", 2);

        assertEquals(2, result.relatedCases().size());
        assertEquals(List.of("case-2", "case-3"),
                result.relatedCases().stream().map(RelatedCaseGroup::caseId).toList());
    }

    @Test
    void aCaseWithTwoChunksCannotConsumeAllResultPositions() {
        FakeEvidenceRetrievalPort retrievalPort = new FakeEvidenceRetrievalPort();
        retrievalPort.seedFocal("case-1", List.of(
                new EvidenceHit("case:case-1:evidence", EvidenceSourceType.CASE_EVIDENCE, "case-1", "focal")));
        retrievalPort.seedHybridResults(List.of(
                new EvidenceHit("case:case-2:evidence", EvidenceSourceType.CASE_EVIDENCE, "case-2", "a"),
                new EvidenceHit("case:case-2:resolution", EvidenceSourceType.RESOLUTION, "case-2", "b"),
                new EvidenceHit("case:case-3:evidence", EvidenceSourceType.CASE_EVIDENCE, "case-3", "c")));

        InvestigationRetrievalResult result = service(retrievalPort).retrieve("case-1", "why?", 3);

        assertEquals(2, result.relatedCases().size());
        RelatedCaseGroup case2 = result.relatedCases().get(0);
        assertEquals("case-2", case2.caseId());
        assertEquals(2, case2.sources().size(), "both chunks of the same related case are grouped together");
        assertEquals("case-3", result.relatedCases().get(1).caseId());
    }

    @Test
    void excerptsAreBoundedToTheConfiguredLength() {
        FakeEvidenceRetrievalPort retrievalPort = new FakeEvidenceRetrievalPort();
        String longText = "x".repeat(1000);
        retrievalPort.seedFocal("case-1", List.of(
                new EvidenceHit("case:case-1:evidence", EvidenceSourceType.CASE_EVIDENCE, "case-1", longText)));

        InvestigationRetrievalResult result = new InvestigationRetrievalService(
                retrievalPort, new FakeEmbeddingPort(DIMENSIONS), 50, 2000, 100)
                .retrieve("case-1", "why?", 5);

        assertTrue(result.focalSources().get(0).excerpt().length() <= 100);
    }
}
