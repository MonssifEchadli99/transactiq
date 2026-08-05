package io.github.monssifechadli99.transactiq.investigation_assistant.application;

import com.google.protobuf.Timestamp;
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudCaseProjectionSnapshot;
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudRuleEvidence;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceDraft;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceSourceType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Strict allowlist mapper from a full projection snapshot to safe, synthetic evidence
 * text. Every field read here is explicitly approved; anything not read (request/event
 * IDs, assignee/resolver identity, snapshot hash, projection version) never reaches the
 * embedded text, the embedding provider, or a REST response.
 */
public final class SafeEvidenceMapper {

    public List<EvidenceDraft> map(FraudCaseProjectionSnapshot snapshot) {
        List<EvidenceDraft> drafts = new ArrayList<>();
        drafts.add(evidenceDraft(snapshot));
        if (hasResolution(snapshot)) {
            drafts.add(resolutionDraft(snapshot));
        }
        return List.copyOf(drafts);
    }

    private static boolean hasResolution(FraudCaseProjectionSnapshot snapshot) {
        return "RESOLVED".equals(snapshot.getStatus())
                && snapshot.hasResolutionOutcome()
                && snapshot.hasResolutionRationale();
    }

    private EvidenceDraft evidenceDraft(FraudCaseProjectionSnapshot snapshot) {
        String caseId = snapshot.getCaseId();
        return new EvidenceDraft(
                "case:" + caseId + ":evidence",
                EvidenceSourceType.CASE_EVIDENCE,
                caseId,
                evidenceText(snapshot),
                snapshot.getAggregateVersion());
    }

    private EvidenceDraft resolutionDraft(FraudCaseProjectionSnapshot snapshot) {
        String caseId = snapshot.getCaseId();
        return new EvidenceDraft(
                "case:" + caseId + ":resolution",
                EvidenceSourceType.RESOLUTION,
                caseId,
                resolutionText(snapshot),
                snapshot.getAggregateVersion());
    }

    private String evidenceText(FraudCaseProjectionSnapshot snapshot) {
        StringBuilder text = new StringBuilder();
        text.append("Fraud case ").append(snapshot.getCaseId())
                .append(" status ").append(snapshot.getStatus()).append(".\n");
        text.append("Merchant ").append(snapshot.getMerchantId())
                .append(" category ").append(snapshot.getMerchantCategoryCode()).append(".\n");
        text.append("Amount ").append(snapshot.getAmount()).append(' ').append(snapshot.getCurrency())
                .append(" in ").append(snapshot.getCountry())
                .append(" via ").append(snapshot.getChannel()).append(".\n");
        text.append("Authorization occurred at ").append(instant(snapshot.getAuthorizationOccurredAt()))
                .append(", decision ").append(snapshot.getAuthorizationDecision()).append(".\n");
        text.append("Fraud assessment ").append(snapshot.getFraudAssessment())
                .append(" with risk score ").append(snapshot.getRiskScore()).append('.');
        List<FraudRuleEvidence> rules = sortedRules(snapshot.getMatchedRulesList());
        if (!rules.isEmpty()) {
            text.append("\nMatched rules:");
            for (FraudRuleEvidence rule : rules) {
                text.append("\n- ").append(rule.getRuleCode())
                        .append(" (").append(rule.getSeverity())
                        .append(", contribution ").append(rule.getScoreContribution())
                        .append("): ").append(rule.getEvidence());
            }
        }
        return text.toString();
    }

    private String resolutionText(FraudCaseProjectionSnapshot snapshot) {
        StringBuilder text = new StringBuilder();
        text.append("Fraud case ").append(snapshot.getCaseId()).append(" resolution.\n");
        text.append("Outcome ").append(snapshot.getResolutionOutcome()).append(".\n");
        text.append("Rationale: ").append(snapshot.getResolutionRationale());
        if (snapshot.hasResolvedAt()) {
            text.append("\nResolved at ").append(instant(snapshot.getResolvedAt())).append('.');
        }
        return text.toString();
    }

    private static List<FraudRuleEvidence> sortedRules(List<FraudRuleEvidence> rules) {
        return rules.stream()
                .sorted(Comparator.comparing(FraudRuleEvidence::getRuleCode)
                        .thenComparing(FraudRuleEvidence::getSeverity)
                        .thenComparing(FraudRuleEvidence::getEvidence)
                        .thenComparingInt(FraudRuleEvidence::getScoreContribution))
                .toList();
    }

    private static String instant(Timestamp value) {
        return Instant.ofEpochSecond(value.getSeconds(), value.getNanos()).toString();
    }
}
