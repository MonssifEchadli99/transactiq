package io.github.monssifechadli99.transactiq.authorization.adapter.out.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationChannel;
import io.github.monssifechadli99.transactiq.authorization.application.model.FraudAssessmentTechnicalException;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessment;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessmentResult;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudRuleMatch;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudRuleSeverity;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.AssessFraudRequest;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.AssessFraudResponse;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.FraudAssessmentOutcome;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.RuleMatch;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.RuleMatchSeverity;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.TransactionChannel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FraudGrpcMapperTest {

    private final FraudGrpcMapper mapper = new FraudGrpcMapper();

    @Test
    void mapsEveryAuthorizationFieldWithoutDecimalOrTimeLoss() {
        AuthorizationCommand command = new AuthorizationCommand(
                UUID.fromString("8014130b-9240-4772-99ca-17e0bcbd5639"),
                "tok_A1B2C3D4",
                "merchant-123",
                "5411",
                new BigDecimal("1234567890.1200"),
                "EUR",
                "DE",
                AuthorizationChannel.POINT_OF_SALE,
                Instant.parse("2026-07-21T12:34:56.123456789Z"));

        AssessFraudRequest request = mapper.toRequest(command);

        assertEquals(command.requestId().toString(), request.getRequestId());
        assertEquals(command.cardToken(), request.getCardToken());
        assertEquals(command.merchantId(), request.getMerchantId());
        assertEquals(command.merchantCategoryCode(), request.getMerchantCategoryCode());
        assertEquals("1234567890.1200", request.getAmount());
        assertEquals(command.currency(), request.getCurrency());
        assertEquals(command.country(), request.getCountry());
        assertEquals(TransactionChannel.TRANSACTION_CHANNEL_POINT_OF_SALE, request.getChannel());
        assertEquals(command.transactionTime().getEpochSecond(), request.getTransactionTime().getSeconds());
        assertEquals(command.transactionTime().getNano(), request.getTransactionTime().getNanos());
    }

    @Test
    void mapsClearReviewHighRiskScoresAndAllOrderedContributions() {
        FraudAssessmentResult clear = mapper.toResult(response(
                FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_CLEAR, 0));
        assertEquals(FraudAssessmentResult.clear(), clear);

        FraudAssessmentResult review = mapper.toResult(response(
                FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_REVIEW,
                15,
                rule("AMOUNT_REVIEW", RuleMatchSeverity.RULE_MATCH_SEVERITY_REVIEW, "amount", 15)));
        assertEquals(FraudAssessment.REVIEW, review.assessment());
        assertEquals(15, review.riskScore());
        assertEquals(
                List.of(new FraudRuleMatch(
                        "AMOUNT_REVIEW", FraudRuleSeverity.REVIEW, "amount", 15)),
                review.matchedRules());

        FraudAssessmentResult highRisk = mapper.toResult(response(
                FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_HIGH_RISK,
                85,
                rule("COUNTRY_REVIEW", RuleMatchSeverity.RULE_MATCH_SEVERITY_REVIEW, "country", 15),
                rule("MCC_HIGH", RuleMatchSeverity.RULE_MATCH_SEVERITY_HIGH_RISK, "mcc", 70)));
        assertEquals(FraudAssessment.HIGH_RISK, highRisk.assessment());
        assertEquals(85, highRisk.riskScore());
        assertEquals(List.of(15, 70), highRisk.matchedRules().stream()
                .map(FraudRuleMatch::scoreContribution)
                .toList());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedResponses")
    void rejectsEveryMalformedFraudResponse(String description, AssessFraudResponse response) {
        assertThrows(FraudAssessmentTechnicalException.class, () -> mapper.toResult(response));
    }

    private static Stream<Arguments> malformedResponses() {
        RuleMatch review = rule(
                "REVIEW_RULE", RuleMatchSeverity.RULE_MATCH_SEVERITY_REVIEW, "review", 15);
        RuleMatch highRisk = rule(
                "HIGH_RULE", RuleMatchSeverity.RULE_MATCH_SEVERITY_HIGH_RISK, "high", 70);
        return Stream.of(
                Arguments.of(
                        "absent risk score",
                        AssessFraudResponse.newBuilder()
                                .setAssessment(FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_CLEAR)
                                .build()),
                Arguments.of(
                        "absent score contribution",
                        AssessFraudResponse.newBuilder()
                                .setAssessment(FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_REVIEW)
                                .setRiskScore(15)
                                .addMatchedRules(RuleMatch.newBuilder()
                                        .setRuleCode("REVIEW_RULE")
                                        .setSeverity(RuleMatchSeverity.RULE_MATCH_SEVERITY_REVIEW)
                                        .setEvidence("review")
                                        .build())
                                .build()),
                Arguments.of("CLEAR with nonzero score", response(
                        FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_CLEAR, 1)),
                Arguments.of("CLEAR with matches", response(
                        FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_CLEAR, 15, review)),
                Arguments.of("REVIEW outside 1 to 69", response(
                        FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_REVIEW,
                        70,
                        rule("REVIEW_RULE", RuleMatchSeverity.RULE_MATCH_SEVERITY_REVIEW, "review", 70))),
                Arguments.of("HIGH_RISK outside 70 to 100", response(
                        FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_HIGH_RISK,
                        69,
                        rule("HIGH_RULE", RuleMatchSeverity.RULE_MATCH_SEVERITY_HIGH_RISK, "high", 69))),
                Arguments.of("zero contribution", response(
                        FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_REVIEW,
                        0,
                        rule("REVIEW_RULE", RuleMatchSeverity.RULE_MATCH_SEVERITY_REVIEW, "review", 0))),
                Arguments.of("contribution above 100", response(
                        FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_HIGH_RISK,
                        100,
                        rule("HIGH_RULE", RuleMatchSeverity.RULE_MATCH_SEVERITY_HIGH_RISK, "high", 101))),
                Arguments.of("total differs from sum", response(
                        FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_REVIEW, 14, review)),
                Arguments.of("total differs from capped sum", response(
                        FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_HIGH_RISK,
                        99,
                        rule("A_HIGH", RuleMatchSeverity.RULE_MATCH_SEVERITY_HIGH_RISK, "high", 70),
                        rule("B_HIGH", RuleMatchSeverity.RULE_MATCH_SEVERITY_HIGH_RISK, "high", 75))),
                Arguments.of("assessment disagrees with severities", response(
                        FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_REVIEW, 70, highRisk)),
                Arguments.of("unspecified assessment", AssessFraudResponse.newBuilder()
                        .setRiskScore(0)
                        .build()),
                Arguments.of("unrecognized assessment", AssessFraudResponse.newBuilder()
                        .setAssessmentValue(99)
                        .setRiskScore(0)
                        .build()),
                Arguments.of("unspecified severity", response(
                        FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_REVIEW,
                        15,
                        RuleMatch.newBuilder()
                                .setRuleCode("RULE")
                                .setEvidence("evidence")
                                .setScoreContribution(15)
                                .build())),
                Arguments.of("unrecognized severity", response(
                        FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_REVIEW,
                        15,
                        RuleMatch.newBuilder()
                                .setRuleCode("RULE")
                                .setSeverityValue(99)
                                .setEvidence("evidence")
                                .setScoreContribution(15)
                                .build())),
                Arguments.of("blank rule code", response(
                        FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_REVIEW,
                        15,
                        rule(" ", RuleMatchSeverity.RULE_MATCH_SEVERITY_REVIEW, "evidence", 15))),
                Arguments.of("blank evidence", response(
                        FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_REVIEW,
                        15,
                        rule("RULE", RuleMatchSeverity.RULE_MATCH_SEVERITY_REVIEW, " ", 15))),
                Arguments.of("review without matches", response(
                        FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_REVIEW, 1)),
                Arguments.of("high risk without matches", response(
                        FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_HIGH_RISK, 70)),
                Arguments.of("matches out of alphabetical order", response(
                        FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_HIGH_RISK,
                        85,
                        review,
                        highRisk)));
    }

    private static AssessFraudResponse response(
            FraudAssessmentOutcome assessment, int riskScore, RuleMatch... matches) {
        return AssessFraudResponse.newBuilder()
                .setAssessment(assessment)
                .setRiskScore(riskScore)
                .addAllMatchedRules(List.of(matches))
                .build();
    }

    private static RuleMatch rule(
            String code,
            RuleMatchSeverity severity,
            String evidence,
            int scoreContribution) {
        return RuleMatch.newBuilder()
                .setRuleCode(code)
                .setSeverity(severity)
                .setEvidence(evidence)
                .setScoreContribution(scoreContribution)
                .build();
    }
}
