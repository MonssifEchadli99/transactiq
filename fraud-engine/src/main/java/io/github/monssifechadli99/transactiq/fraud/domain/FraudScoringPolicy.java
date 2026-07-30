package io.github.monssifechadli99.transactiq.fraud.domain;

import io.github.monssifechadli99.transactiq.fraud.domain.rule.AmountThresholdFraudRule;
import io.github.monssifechadli99.transactiq.fraud.domain.rule.CountrySwitchFraudRule;
import io.github.monssifechadli99.transactiq.fraud.domain.rule.MerchantProfileFraudRule;
import io.github.monssifechadli99.transactiq.fraud.domain.rule.RiskyMerchantCategoryCodeFraudRule;
import io.github.monssifechadli99.transactiq.fraud.domain.rule.RollingAmountFraudRule;
import io.github.monssifechadli99.transactiq.fraud.domain.rule.TransactionCountFraudRule;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class FraudScoringPolicy {

    private static final Set<ScoreKey> REQUIRED_SCORE_KEYS = Set.of(
            new ScoreKey(AmountThresholdFraudRule.RULE_CODE, FraudRuleSeverity.REVIEW),
            new ScoreKey(AmountThresholdFraudRule.RULE_CODE, FraudRuleSeverity.HIGH_RISK),
            new ScoreKey(MerchantProfileFraudRule.RULE_CODE, FraudRuleSeverity.REVIEW),
            new ScoreKey(MerchantProfileFraudRule.RULE_CODE, FraudRuleSeverity.HIGH_RISK),
            new ScoreKey(RiskyMerchantCategoryCodeFraudRule.RULE_CODE, FraudRuleSeverity.REVIEW),
            new ScoreKey(RiskyMerchantCategoryCodeFraudRule.RULE_CODE, FraudRuleSeverity.HIGH_RISK),
            new ScoreKey(TransactionCountFraudRule.RULE_CODE, FraudRuleSeverity.REVIEW),
            new ScoreKey(TransactionCountFraudRule.RULE_CODE, FraudRuleSeverity.HIGH_RISK),
            new ScoreKey(RollingAmountFraudRule.RULE_CODE, FraudRuleSeverity.REVIEW),
            new ScoreKey(RollingAmountFraudRule.RULE_CODE, FraudRuleSeverity.HIGH_RISK),
            new ScoreKey(CountrySwitchFraudRule.RULE_CODE, FraudRuleSeverity.HIGH_RISK));

    private final Map<ScoreKey, Integer> contributions;

    public FraudScoringPolicy(List<ConfiguredContribution> configuredContributions) {
        Objects.requireNonNull(configuredContributions, "configuredContributions must not be null");
        Map<ScoreKey, Integer> validated = new HashMap<>();
        for (ConfiguredContribution configured : configuredContributions) {
            Objects.requireNonNull(configured, "configured contribution must not be null");
            ScoreKey key = new ScoreKey(configured.ruleCode(), configured.severity());
            if (!REQUIRED_SCORE_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                        "unsupported fraud score mapping " + key.ruleCode() + "/" + key.severity());
            }
            if (configured.points() < 1 || configured.points() > 100) {
                throw new IllegalArgumentException("fraud score contribution must be between 1 and 100");
            }
            if (configured.severity() == FraudRuleSeverity.HIGH_RISK && configured.points() < 70) {
                throw new IllegalArgumentException("HIGH_RISK fraud score contribution must be at least 70");
            }
            if (validated.putIfAbsent(key, configured.points()) != null) {
                throw new IllegalArgumentException(
                        "duplicate fraud score mapping " + key.ruleCode() + "/" + key.severity());
            }
        }

        Set<ScoreKey> missing = new HashSet<>(REQUIRED_SCORE_KEYS);
        missing.removeAll(validated.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("missing fraud score mappings: " + missing);
        }

        int maximumReviewScore = validated.entrySet().stream()
                .filter(entry -> entry.getKey().severity() == FraudRuleSeverity.REVIEW)
                .mapToInt(Map.Entry::getValue)
                .sum();
        if (maximumReviewScore > 69) {
            throw new IllegalArgumentException(
                    "sum of all REVIEW fraud score contributions must not exceed 69");
        }
        contributions = Map.copyOf(validated);
    }

    public FraudRiskScore score(List<MatchedFraudRule> matchedRules) {
        Objects.requireNonNull(matchedRules, "matchedRules must not be null");
        Set<String> matchedRuleCodes = new HashSet<>();
        List<ScoredFraudRuleMatch> scoredMatches = matchedRules.stream()
                .map(match -> {
                    Objects.requireNonNull(match, "matched rule must not be null");
                    if (!matchedRuleCodes.add(match.ruleCode())) {
                        throw new IllegalArgumentException(
                                "fraud rule must contribute at most once: " + match.ruleCode());
                    }
                    Integer points = contributions.get(new ScoreKey(match.ruleCode(), match.severity()));
                    if (points == null) {
                        throw new IllegalArgumentException(
                                "no fraud score mapping for " + match.ruleCode() + "/" + match.severity());
                    }
                    return new ScoredFraudRuleMatch(
                            match.ruleCode(), match.severity(), match.evidence(), points);
                })
                .toList();
        int score = (int) Math.min(
                100L,
                scoredMatches.stream()
                        .mapToLong(ScoredFraudRuleMatch::scoreContribution)
                        .sum());
        return new FraudRiskScore(score, scoredMatches);
    }

    public record ConfiguredContribution(
            String ruleCode, FraudRuleSeverity severity, int points) {

        public ConfiguredContribution {
            if (ruleCode == null || ruleCode.isBlank()) {
                throw new IllegalArgumentException("fraud score ruleCode must not be blank");
            }
            Objects.requireNonNull(severity, "fraud score severity must not be null");
        }
    }

    private record ScoreKey(String ruleCode, FraudRuleSeverity severity) {}
}
