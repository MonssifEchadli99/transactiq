package io.github.monssifechadli99.transactiq.fraud.application.service;

import io.github.monssifechadli99.transactiq.fraud.application.port.in.FraudAssessmentUseCase;
import io.github.monssifechadli99.transactiq.fraud.application.port.out.VelocityAttemptRecorder;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessment;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentRequest;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentResult;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudRuleSeverity;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudRiskScore;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudScoringPolicy;
import io.github.monssifechadli99.transactiq.fraud.domain.MatchedFraudRule;
import io.github.monssifechadli99.transactiq.fraud.domain.rule.FraudRule;
import io.github.monssifechadli99.transactiq.fraud.domain.rule.FraudRuleContext;
import io.github.monssifechadli99.transactiq.fraud.domain.velocity.VelocitySnapshot;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class RuleBasedFraudAssessmentService implements FraudAssessmentUseCase {

    private static final Comparator<MatchedFraudRule> MATCH_ORDER = Comparator
            .comparing(MatchedFraudRule::ruleCode)
            .thenComparing(MatchedFraudRule::severity)
            .thenComparing(MatchedFraudRule::evidence);

    private final List<FraudRule> rules;
    private final VelocityAttemptRecorder velocityAttemptRecorder;
    private final Clock clock;
    private final FraudScoringPolicy scoringPolicy;

    public RuleBasedFraudAssessmentService(
            List<FraudRule> rules,
            VelocityAttemptRecorder velocityAttemptRecorder,
            Clock clock,
            FraudScoringPolicy scoringPolicy) {
        Objects.requireNonNull(rules, "rules must not be null");
        this.rules = rules.stream()
                .map(rule -> Objects.requireNonNull(rule, "rule must not be null"))
                .toList();
        this.velocityAttemptRecorder = Objects.requireNonNull(
                velocityAttemptRecorder,
                "velocityAttemptRecorder must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.scoringPolicy = Objects.requireNonNull(scoringPolicy, "scoringPolicy must not be null");
    }

    @Override
    public FraudAssessmentResult assess(FraudAssessmentRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        VelocitySnapshot velocitySnapshot = Objects.requireNonNull(
                velocityAttemptRecorder.recordAttemptAndGetSnapshot(request, clock.instant()),
                "velocity snapshot must not be null");
        FraudRuleContext context = new FraudRuleContext(request, velocitySnapshot);

        List<MatchedFraudRule> matchedRules = rules.stream()
                .map(rule -> Objects.requireNonNull(
                        rule.evaluate(context),
                        "rule evaluation must not return null"))
                .flatMap(java.util.Optional::stream)
                .sorted(MATCH_ORDER)
                .toList();

        FraudAssessment assessment = matchedRules.stream()
                .map(MatchedFraudRule::severity)
                .max(Comparator.naturalOrder())
                .map(RuleBasedFraudAssessmentService::toAssessment)
                .orElse(FraudAssessment.CLEAR);

        FraudRiskScore riskScore = scoringPolicy.score(matchedRules);
        return new FraudAssessmentResult(assessment, riskScore.value(), riskScore.matchedRules());
    }

    private static FraudAssessment toAssessment(FraudRuleSeverity severity) {
        return switch (severity) {
            case REVIEW -> FraudAssessment.REVIEW;
            case HIGH_RISK -> FraudAssessment.HIGH_RISK;
        };
    }
}
