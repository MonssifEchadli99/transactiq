package io.github.monssifechadli99.transactiq.investigation_assistant.domain;

import java.util.List;

/** A validated, evidence-grounded, advisory investigation answer. */
public record InvestigationAnswerResult(
        String caseId,
        String summary,
        List<GroundedFinding> findings,
        List<String> recommendedChecks,
        GroundingStatus groundingStatus) {
    public InvestigationAnswerResult {
        findings = List.copyOf(findings);
        recommendedChecks = List.copyOf(recommendedChecks);
    }

    @Override
    public String toString() {
        return "InvestigationAnswerResult[caseId=<redacted>, content=<redacted>, findingCount="
                + findings.size() + ", recommendedCheckCount=" + recommendedChecks.size()
                + ", groundingStatus=" + groundingStatus + "]";
    }
}
