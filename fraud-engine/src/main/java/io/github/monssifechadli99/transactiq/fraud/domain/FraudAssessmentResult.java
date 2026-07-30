package io.github.monssifechadli99.transactiq.fraud.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record FraudAssessmentResult(
        FraudAssessment assessment,
        int riskScore,
        List<ScoredFraudRuleMatch> matchedRules) {

    public FraudAssessmentResult {
        Objects.requireNonNull(assessment, "assessment must not be null");
        Objects.requireNonNull(matchedRules, "matchedRules must not be null");
        matchedRules = List.copyOf(matchedRules);

        int expectedScore = (int) Math.min(
                100L,
                matchedRules.stream()
                        .mapToLong(ScoredFraudRuleMatch::scoreContribution)
                        .sum());
        if (riskScore != expectedScore) {
            throw new IllegalArgumentException(
                    "riskScore must equal the capped contribution sum: expected "
                            + expectedScore + " but was " + riskScore);
        }
        for (int index = 1; index < matchedRules.size(); index++) {
            String previousRuleCode = matchedRules.get(index - 1).ruleCode();
            String currentRuleCode = matchedRules.get(index).ruleCode();
            if (previousRuleCode.compareTo(currentRuleCode) >= 0) {
                throw new IllegalArgumentException(
                        "matchedRules must contain unique rule codes in alphabetical order");
            }
        }

        FraudAssessment expectedAssessment = highestSeverityAssessment(matchedRules);
        if (assessment != expectedAssessment) {
            throw new IllegalArgumentException(
                    "assessment must equal the highest matched-rule severity: expected "
                            + expectedAssessment + " but was " + assessment);
        }
        boolean validBand = switch (assessment) {
            case CLEAR -> riskScore == 0;
            case REVIEW -> riskScore >= 1 && riskScore <= 69;
            case HIGH_RISK -> riskScore >= 70 && riskScore <= 100;
        };
        if (!validBand) {
            throw new IllegalArgumentException(
                    "riskScore " + riskScore + " is inconsistent with assessment " + assessment);
        }
    }

    private static FraudAssessment highestSeverityAssessment(List<ScoredFraudRuleMatch> matchedRules) {
        Optional<FraudRuleSeverity> highestSeverity = matchedRules.stream()
                .map(ScoredFraudRuleMatch::severity)
                .max(Comparator.naturalOrder());

        return highestSeverity.map(severity -> switch (severity) {
            case REVIEW -> FraudAssessment.REVIEW;
            case HIGH_RISK -> FraudAssessment.HIGH_RISK;
        }).orElse(FraudAssessment.CLEAR);
    }
}
