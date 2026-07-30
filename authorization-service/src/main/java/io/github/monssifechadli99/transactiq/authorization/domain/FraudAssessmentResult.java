package io.github.monssifechadli99.transactiq.authorization.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record FraudAssessmentResult(
        FraudAssessment assessment,
        int riskScore,
        List<FraudRuleMatch> matchedRules) {

    public FraudAssessmentResult {
        Objects.requireNonNull(assessment, "assessment must not be null");
        matchedRules = List.copyOf(
                Objects.requireNonNull(matchedRules, "matchedRules must not be null"));
        long contributionSum = matchedRules.stream()
                .mapToLong(FraudRuleMatch::scoreContribution)
                .sum();
        int expectedScore = (int) Math.min(100L, contributionSum);
        if (riskScore != expectedScore) {
            throw new IllegalArgumentException(
                    "riskScore must equal the capped contribution sum");
        }
        for (int index = 1; index < matchedRules.size(); index++) {
            if (matchedRules.get(index - 1).ruleCode()
                            .compareTo(matchedRules.get(index).ruleCode())
                    >= 0) {
                throw new IllegalArgumentException(
                        "matchedRules must contain unique rule codes in alphabetical order");
            }
        }

        FraudAssessment expectedAssessment = matchedRules.stream()
                .map(FraudRuleMatch::severity)
                .max(Comparator.naturalOrder())
                .map(severity -> switch (severity) {
                    case REVIEW -> FraudAssessment.REVIEW;
                    case HIGH_RISK -> FraudAssessment.HIGH_RISK;
                })
                .orElse(FraudAssessment.CLEAR);
        if (assessment != expectedAssessment) {
            throw new IllegalArgumentException(
                    "assessment must equal the highest matched-rule severity");
        }

        boolean validBand = switch (assessment) {
            case CLEAR -> riskScore == 0;
            case REVIEW -> riskScore >= 1 && riskScore <= 69;
            case HIGH_RISK -> riskScore >= 70 && riskScore <= 100;
        };
        if (!validBand) {
            throw new IllegalArgumentException(
                    "riskScore is inconsistent with assessment " + assessment);
        }
    }

    public static FraudAssessmentResult clear() {
        return new FraudAssessmentResult(FraudAssessment.CLEAR, 0, List.of());
    }
}
