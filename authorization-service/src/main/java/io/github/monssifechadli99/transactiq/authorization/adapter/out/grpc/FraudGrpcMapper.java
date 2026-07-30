package io.github.monssifechadli99.transactiq.authorization.adapter.out.grpc;

import com.google.protobuf.Timestamp;
import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationChannel;
import io.github.monssifechadli99.transactiq.authorization.application.model.FraudAssessmentTechnicalException;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessment;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessmentResult;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudRuleMatch;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudRuleSeverity;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.AssessFraudRequest;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.AssessFraudResponse;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.TransactionChannel;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class FraudGrpcMapper {

    public AssessFraudRequest toRequest(AuthorizationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        return AssessFraudRequest.newBuilder()
                .setRequestId(required(command.requestId(), "requestId").toString())
                .setCardToken(required(command.cardToken(), "cardToken"))
                .setMerchantId(required(command.merchantId(), "merchantId"))
                .setMerchantCategoryCode(required(
                        command.merchantCategoryCode(), "merchantCategoryCode"))
                .setAmount(required(command.amount(), "amount").toPlainString())
                .setCurrency(required(command.currency(), "currency"))
                .setCountry(required(command.country(), "country"))
                .setChannel(toContractChannel(required(command.channel(), "channel")))
                .setTransactionTime(toTimestamp(required(
                        command.transactionTime(), "transactionTime")))
                .build();
    }

    public FraudAssessmentResult toResult(AssessFraudResponse response) {
        Objects.requireNonNull(response, "response must not be null");
        if (!response.hasRiskScore()) {
            throw malformed("Fraud assessment response has no risk score");
        }
        FraudAssessment assessment = switch (response.getAssessment()) {
            case FRAUD_ASSESSMENT_OUTCOME_CLEAR -> FraudAssessment.CLEAR;
            case FRAUD_ASSESSMENT_OUTCOME_REVIEW -> FraudAssessment.REVIEW;
            case FRAUD_ASSESSMENT_OUTCOME_HIGH_RISK -> FraudAssessment.HIGH_RISK;
            case FRAUD_ASSESSMENT_OUTCOME_UNSPECIFIED, UNRECOGNIZED ->
                    throw malformed("Fraud assessment is unspecified or unrecognized");
        };

        List<FraudRuleMatch> matchedRules = response.getMatchedRulesList().stream()
                .map(this::toRuleMatch)
                .toList();
        try {
            return new FraudAssessmentResult(
                    assessment, response.getRiskScore(), matchedRules);
        } catch (IllegalArgumentException inconsistentResponse) {
            throw new FraudAssessmentTechnicalException(
                    "Fraud assessment response is internally inconsistent",
                    inconsistentResponse);
        }
    }

    private FraudRuleMatch toRuleMatch(
            io.github.monssifechadli99.transactiq.fraud.contract.v1.RuleMatch match) {
        FraudRuleSeverity severity = switch (match.getSeverity()) {
            case RULE_MATCH_SEVERITY_REVIEW -> FraudRuleSeverity.REVIEW;
            case RULE_MATCH_SEVERITY_HIGH_RISK -> FraudRuleSeverity.HIGH_RISK;
            case RULE_MATCH_SEVERITY_UNSPECIFIED, UNRECOGNIZED ->
                    throw malformed("Fraud rule severity is unspecified or unrecognized");
        };
        if (!match.hasScoreContribution()) {
            throw malformed("Fraud rule match has no score contribution");
        }
        try {
            return new FraudRuleMatch(
                    match.getRuleCode(),
                    severity,
                    match.getEvidence(),
                    match.getScoreContribution());
        } catch (IllegalArgumentException invalidMatch) {
            throw new FraudAssessmentTechnicalException(
                    "Fraud assessment response contains an invalid rule match", invalidMatch);
        }
    }

    private static TransactionChannel toContractChannel(AuthorizationChannel channel) {
        return switch (channel) {
            case ECOMMERCE -> TransactionChannel.TRANSACTION_CHANNEL_ECOMMERCE;
            case POINT_OF_SALE -> TransactionChannel.TRANSACTION_CHANNEL_POINT_OF_SALE;
        };
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static FraudAssessmentTechnicalException malformed(String message) {
        return new FraudAssessmentTechnicalException(message);
    }

    private static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, name + " must not be null");
    }
}
