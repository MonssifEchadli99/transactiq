package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.mcp;

import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.mcp.McpInvestigationResponse.Answer;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.mcp.McpInvestigationResponse.Citation;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.mcp.McpInvestigationResponse.Finding;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.mcp.McpInvestigationResponse.RelatedCase;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.mcp.McpInvestigationResponse.Retrieval;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.mcp.McpInvestigationResponse.Source;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GroundedFinding;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GroundingCitation;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.InvestigationAnswerResult;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.InvestigationRetrievalResult;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.RelatedCaseGroup;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.RetrievedSource;

public final class McpInvestigationMapper {

    public Retrieval toRetrieval(InvestigationRetrievalResult result) {
        return new Retrieval(
                result.focalCaseId(),
                result.focalSources().stream().map(this::toSource).toList(),
                result.relatedCases().stream().map(this::toRelatedCase).toList());
    }

    public Answer toAnswer(InvestigationAnswerResult result) {
        return new Answer(
                result.caseId(),
                result.summary(),
                result.findings().stream().map(this::toFinding).toList(),
                result.recommendedChecks(),
                result.groundingStatus().name());
    }

    private RelatedCase toRelatedCase(RelatedCaseGroup group) {
        return new RelatedCase(group.caseId(), group.sources().stream().map(this::toSource).toList());
    }

    private Source toSource(RetrievedSource source) {
        return new Source(source.sourceId(), source.sourceType().name(), source.caseId(), source.excerpt());
    }

    private Finding toFinding(GroundedFinding finding) {
        return new Finding(finding.text(), finding.citations().stream().map(this::toCitation).toList());
    }

    private Citation toCitation(GroundingCitation citation) {
        return new Citation(
                citation.sourceId(), citation.sourceType().name(), citation.caseId(), citation.excerpt());
    }
}
