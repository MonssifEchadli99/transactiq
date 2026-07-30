package io.github.monssifechadli99.transactiq.fraud.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.fraud.application.port.out.VelocityAttemptRecorder;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessment;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentRequest;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentResult;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudChannel;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudRuleSeverity;
import io.github.monssifechadli99.transactiq.fraud.domain.MatchedFraudRule;
import io.github.monssifechadli99.transactiq.fraud.domain.ScoredFraudRuleMatch;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudScoringPolicy;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudScoringPolicy.ConfiguredContribution;
import io.github.monssifechadli99.transactiq.fraud.domain.rule.FraudRule;
import io.github.monssifechadli99.transactiq.fraud.domain.velocity.VelocitySnapshot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RuleBasedFraudAssessmentServiceTest {

    private static final Instant OBSERVATION_TIME = Instant.parse("2026-07-19T10:16:00Z");

    private static final MatchedFraudRule AMOUNT_MATCH = new MatchedFraudRule(
            "AMOUNT_THRESHOLD",
            FraudRuleSeverity.HIGH_RISK,
            "amount EUR 2600.00 met synthetic HIGH_RISK threshold 2500.00");
    private static final MatchedFraudRule MERCHANT_MATCH = new MatchedFraudRule(
            "MERCHANT_PROFILE",
            FraudRuleSeverity.REVIEW,
            "merchant merchant-review has synthetic REVIEW profile");
    private static final MatchedFraudRule MCC_MATCH = new MatchedFraudRule(
            "RISKY_MCC",
            FraudRuleSeverity.REVIEW,
            "MCC 7995 has synthetic REVIEW classification");
    private static final MatchedFraudRule COUNTRY_MATCH = new MatchedFraudRule(
            "COUNTRY_SWITCH",
            FraudRuleSeverity.HIGH_RISK,
            "current country FR differs from observed country DE");
    private static final MatchedFraudRule ROLLING_AMOUNT_MATCH = new MatchedFraudRule(
            "ROLLING_AMOUNT",
            FraudRuleSeverity.REVIEW,
            "rolling total EUR 3000.00 met REVIEW threshold 3000.00");
    private static final MatchedFraudRule TRANSACTION_COUNT_MATCH = new MatchedFraudRule(
            "TRANSACTION_COUNT",
            FraudRuleSeverity.REVIEW,
            "5 attempts met REVIEW threshold 5");

    @Test
    void noMatchesProducesClear() {
        RuleBasedFraudAssessmentService service = service(
                List.of(context -> Optional.empty()));

        FraudAssessmentResult result = service.assess(validRequest());

        assertEquals(FraudAssessment.CLEAR, result.assessment());
        assertEquals(0, result.riskScore());
        assertTrue(result.matchedRules().isEmpty());
    }

    @Test
    void reviewOnlyMatchesProduceReviewAndPreserveAllMatchDetails() {
        RuleBasedFraudAssessmentService service = service(
                List.of(fixedRule(MCC_MATCH), fixedRule(MERCHANT_MATCH)));

        FraudAssessmentResult result = service.assess(validRequest());

        assertEquals(FraudAssessment.REVIEW, result.assessment());
        assertEquals(25, result.riskScore());
        assertEquals(List.of("MERCHANT_PROFILE", "RISKY_MCC"), result.matchedRules().stream()
                .map(ScoredFraudRuleMatch::ruleCode)
                .toList());
    }

    @Test
    void anyHighRiskMatchProducesHighRiskAndPreservesReviewMatches() {
        RuleBasedFraudAssessmentService service = service(
                List.of(fixedRule(MERCHANT_MATCH), fixedRule(AMOUNT_MATCH), fixedRule(MCC_MATCH)));

        FraudAssessmentResult result = service.assess(validRequest());

        assertEquals(FraudAssessment.HIGH_RISK, result.assessment());
        assertEquals(95, result.riskScore());
        assertEquals(List.of("AMOUNT_THRESHOLD", "MERCHANT_PROFILE", "RISKY_MCC"),
                result.matchedRules().stream().map(ScoredFraudRuleMatch::ruleCode).toList());
    }

    @Test
    void allFiveReviewMatchesProduceSixtyFiveAndRemainReview() {
        MatchedFraudRule amountReview = new MatchedFraudRule(
                "AMOUNT_THRESHOLD", FraudRuleSeverity.REVIEW, "synthetic amount review");
        RuleBasedFraudAssessmentService service = service(List.of(
                fixedRule(TRANSACTION_COUNT_MATCH),
                fixedRule(MCC_MATCH),
                fixedRule(amountReview),
                fixedRule(ROLLING_AMOUNT_MATCH),
                fixedRule(MERCHANT_MATCH)));

        FraudAssessmentResult result = service.assess(validRequest());

        assertEquals(FraudAssessment.REVIEW, result.assessment());
        assertEquals(65, result.riskScore());
        assertEquals(5, result.matchedRules().size());
    }

    @Test
    void changingAContributionChangesTheScoreButNotTheAssessment() {
        FraudAssessmentResult defaultScore = service(
                        List.of(fixedRule(MERCHANT_MATCH)), scoringPolicy(15))
                .assess(validRequest());
        FraudAssessmentResult changedScore = service(
                        List.of(fixedRule(MERCHANT_MATCH)), scoringPolicy(1))
                .assess(validRequest());

        assertEquals(FraudAssessment.REVIEW, defaultScore.assessment());
        assertEquals(FraudAssessment.REVIEW, changedScore.assessment());
        assertEquals(15, defaultScore.riskScore());
        assertEquals(1, changedScore.riskScore());
    }

    @Test
    void allSixMatchedRuleCodesUseDeterministicAlphabeticalOrder() {
        RuleBasedFraudAssessmentService forward = service(
                List.of(
                        fixedRule(AMOUNT_MATCH),
                        fixedRule(COUNTRY_MATCH),
                        fixedRule(MERCHANT_MATCH),
                        fixedRule(MCC_MATCH),
                        fixedRule(ROLLING_AMOUNT_MATCH),
                        fixedRule(TRANSACTION_COUNT_MATCH)));
        RuleBasedFraudAssessmentService reverse = service(
                List.of(
                        fixedRule(TRANSACTION_COUNT_MATCH),
                        fixedRule(ROLLING_AMOUNT_MATCH),
                        fixedRule(MCC_MATCH),
                        fixedRule(MERCHANT_MATCH),
                        fixedRule(COUNTRY_MATCH),
                        fixedRule(AMOUNT_MATCH)));

        List<String> expectedOrder = List.of(
                "AMOUNT_THRESHOLD",
                "COUNTRY_SWITCH",
                "MERCHANT_PROFILE",
                "RISKY_MCC",
                "ROLLING_AMOUNT",
                "TRANSACTION_COUNT");
        assertEquals(expectedOrder, forward.assess(validRequest()).matchedRules().stream()
                .map(ScoredFraudRuleMatch::ruleCode).toList());
        assertEquals(expectedOrder, reverse.assess(validRequest()).matchedRules().stream()
                .map(ScoredFraudRuleMatch::ruleCode).toList());
    }

    @Test
    void obtainsOneSnapshotAndEvaluatesAllSixConfiguredRulesExactlyOnce() {
        List<AtomicInteger> evaluationCounts = new ArrayList<>();
        List<FraudRule> rules = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            AtomicInteger count = new AtomicInteger();
            evaluationCounts.add(count);
            rules.add(context -> {
                count.incrementAndGet();
                return Optional.empty();
            });
        }
        RecordingVelocityAttemptRecorder recorder = new RecordingVelocityAttemptRecorder();
        RuleBasedFraudAssessmentService service = new RuleBasedFraudAssessmentService(
                rules,
                recorder,
                Clock.fixed(OBSERVATION_TIME, ZoneOffset.UTC),
                scoringPolicy());

        service.assess(validRequest());

        assertEquals(1, recorder.invocations.get());
        assertEquals(OBSERVATION_TIME, recorder.observedAt);
        evaluationCounts.forEach(count -> assertEquals(1, count.get()));
    }

    @Test
    void rejectsNullRequest() {
        RuleBasedFraudAssessmentService service = service(List.of());

        assertThrows(NullPointerException.class, () -> service.assess(null));
    }

    private static FraudRule fixedRule(MatchedFraudRule match) {
        return context -> Optional.of(match);
    }

    private static RuleBasedFraudAssessmentService service(List<FraudRule> rules) {
        return service(rules, scoringPolicy());
    }

    private static RuleBasedFraudAssessmentService service(
            List<FraudRule> rules, FraudScoringPolicy scoringPolicy) {
        return new RuleBasedFraudAssessmentService(
                rules,
                new RecordingVelocityAttemptRecorder(),
                Clock.fixed(OBSERVATION_TIME, ZoneOffset.UTC),
                scoringPolicy);
    }

    private static FraudScoringPolicy scoringPolicy() {
        return scoringPolicy(15);
    }

    private static FraudScoringPolicy scoringPolicy(int merchantReviewContribution) {
        return new FraudScoringPolicy(List.of(
                new ConfiguredContribution("AMOUNT_THRESHOLD", FraudRuleSeverity.REVIEW, 15),
                new ConfiguredContribution("AMOUNT_THRESHOLD", FraudRuleSeverity.HIGH_RISK, 70),
                new ConfiguredContribution(
                        "MERCHANT_PROFILE",
                        FraudRuleSeverity.REVIEW,
                        merchantReviewContribution),
                new ConfiguredContribution("MERCHANT_PROFILE", FraudRuleSeverity.HIGH_RISK, 75),
                new ConfiguredContribution("RISKY_MCC", FraudRuleSeverity.REVIEW, 10),
                new ConfiguredContribution("RISKY_MCC", FraudRuleSeverity.HIGH_RISK, 70),
                new ConfiguredContribution("TRANSACTION_COUNT", FraudRuleSeverity.REVIEW, 10),
                new ConfiguredContribution("TRANSACTION_COUNT", FraudRuleSeverity.HIGH_RISK, 70),
                new ConfiguredContribution("ROLLING_AMOUNT", FraudRuleSeverity.REVIEW, 15),
                new ConfiguredContribution("ROLLING_AMOUNT", FraudRuleSeverity.HIGH_RISK, 70),
                new ConfiguredContribution("COUNTRY_SWITCH", FraudRuleSeverity.HIGH_RISK, 80)));
    }

    private static FraudAssessmentRequest validRequest() {
        return new FraudAssessmentRequest(
                UUID.fromString("f2b1c9d0-6e3a-4c1b-9b7a-2b6a1e9c7d44"),
                "tok_A1B2C3D4",
                "merchant-review",
                "7995",
                new BigDecimal("2600.00"),
                "EUR",
                "DE",
                FraudChannel.ECOMMERCE,
                Instant.parse("2026-07-19T10:15:30Z"));
    }

    private static final class RecordingVelocityAttemptRecorder
            implements VelocityAttemptRecorder {

        private final AtomicInteger invocations = new AtomicInteger();
        private Instant observedAt;

        @Override
        public VelocitySnapshot recordAttemptAndGetSnapshot(
                FraudAssessmentRequest request,
                Instant observedAt) {
            invocations.incrementAndGet();
            this.observedAt = observedAt;
            return new VelocitySnapshot(
                    observedAt,
                    1,
                    Map.of(request.currency(), request.amount()),
                    Set.of(request.country()));
        }
    }
}
