package io.github.monssifechadli99.transactiq.investigation_assistant.domain;

import java.util.List;
import java.util.Objects;

/** Untrusted provider output awaiting grounding validation. */
public record GeneratedAnswerDraft(
        String summary,
        List<GeneratedFindingDraft> findings,
        List<String> recommendedChecks,
        GroundingStatus groundingStatus) {

    public GeneratedAnswerDraft {
        Objects.requireNonNull(summary, "summary must not be null");
        findings = List.copyOf(Objects.requireNonNull(findings, "findings must not be null"));
        recommendedChecks = List.copyOf(
                Objects.requireNonNull(recommendedChecks, "recommendedChecks must not be null"));
        Objects.requireNonNull(groundingStatus, "groundingStatus must not be null");
    }

    @Override
    public String toString() {
        return "GeneratedAnswerDraft[summary=<redacted>, findingCount=" + findings.size()
                + ", recommendedCheckCount=" + recommendedChecks.size()
                + ", groundingStatus=" + groundingStatus + "]";
    }
}
