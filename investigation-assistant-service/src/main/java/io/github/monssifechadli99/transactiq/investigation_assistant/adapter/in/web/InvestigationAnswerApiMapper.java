package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web;

import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GroundedFinding;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GroundingCitation;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.InvestigationAnswerResult;

public final class InvestigationAnswerApiMapper {

    public InvestigationAnswerResponse.Response toResponse(InvestigationAnswerResult result) {
        return new InvestigationAnswerResponse.Response(
                result.caseId(),
                result.summary(),
                result.findings().stream().map(this::toFinding).toList(),
                result.recommendedChecks(),
                result.groundingStatus().name());
    }

    private InvestigationAnswerResponse.Finding toFinding(GroundedFinding finding) {
        return new InvestigationAnswerResponse.Finding(
                finding.text(), finding.citations().stream().map(this::toCitation).toList());
    }

    private InvestigationAnswerResponse.Citation toCitation(GroundingCitation citation) {
        return new InvestigationAnswerResponse.Citation(
                citation.sourceId(), citation.sourceType().name(), citation.caseId(), citation.excerpt());
    }
}
