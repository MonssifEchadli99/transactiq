package io.github.monssifechadli99.transactiq.fraud.adapter.in.grpc;

import com.google.protobuf.Timestamp;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.AssessFraudRequest;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.AssessFraudResponse;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.FraudAssessmentOutcome;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.RuleMatch;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.RuleMatchSeverity;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.TransactionChannel;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessment;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentRequest;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentResult;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudChannel;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudRuleSeverity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class FraudAssessmentGrpcMapper {

    private static final Pattern CARD_TOKEN_PATTERN =
            Pattern.compile("tok_[A-Za-z0-9]{8,60}");
    private static final Pattern MERCHANT_CATEGORY_CODE_PATTERN = Pattern.compile("[0-9]{4}");
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("[A-Z]{3}");
    private static final Pattern COUNTRY_PATTERN = Pattern.compile("[A-Z]{2}");

    private static final int MAX_MERCHANT_ID_LENGTH = 64;
    private static final int MAX_AMOUNT_INTEGER_DIGITS = 12;
    private static final int MAX_AMOUNT_FRACTION_DIGITS = 2;

    private static final long PROTOBUF_TIMESTAMP_MIN_SECONDS = -62_135_596_800L;
    private static final long PROTOBUF_TIMESTAMP_MAX_SECONDS = 253_402_300_799L;
    private static final int PROTOBUF_TIMESTAMP_MAX_NANOS = 999_999_999;

    public FraudAssessmentRequest toDomainRequest(AssessFraudRequest request) {
        return new FraudAssessmentRequest(
                parseRequestId(request.getRequestId()),
                requireValidCardToken(request.getCardToken()),
                requireValidMerchantId(request.getMerchantId()),
                requireMatching(
                        request.getMerchantCategoryCode(),
                        "merchantCategoryCode",
                        MERCHANT_CATEGORY_CODE_PATTERN),
                parseAmount(request.getAmount()),
                requireMatching(request.getCurrency(), "currency", CURRENCY_PATTERN),
                requireMatching(request.getCountry(), "country", COUNTRY_PATTERN),
                toDomainChannel(request.getChannel()),
                toDomainTransactionTime(request));
    }

    public AssessFraudResponse toContractResponse(FraudAssessmentResult result) {
        AssessFraudResponse.Builder response = AssessFraudResponse.newBuilder()
                .setAssessment(toContractOutcome(result.assessment()))
                .setRiskScore(result.riskScore());
        for (var matchedRule : result.matchedRules()) {
            response.addMatchedRules(RuleMatch.newBuilder()
                    .setRuleCode(matchedRule.ruleCode())
                    .setSeverity(toContractSeverity(matchedRule.severity()))
                    .setEvidence(matchedRule.evidence())
                    .setScoreContribution(matchedRule.scoreContribution())
                    .build());
        }
        return response.build();
    }

    private static UUID parseRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new FraudAssessmentRequestRejectedException("requestId must not be blank");
        }
        try {
            return UUID.fromString(requestId);
        } catch (IllegalArgumentException e) {
            throw new FraudAssessmentRequestRejectedException("requestId must be a valid UUID");
        }
    }

    private static String requireValidCardToken(String cardToken) {
        if (cardToken == null || !CARD_TOKEN_PATTERN.matcher(cardToken).matches()) {
            throw new FraudAssessmentRequestRejectedException(
                    "cardToken must match the required synthetic token format");
        }
        return cardToken;
    }

    private static String requireValidMerchantId(String merchantId) {
        requireNonBlank(merchantId, "merchantId");
        if (merchantId.length() > MAX_MERCHANT_ID_LENGTH) {
            throw new FraudAssessmentRequestRejectedException(
                    "merchantId must contain at most " + MAX_MERCHANT_ID_LENGTH + " characters");
        }
        return merchantId;
    }

    private static String requireMatching(String value, String fieldName, Pattern pattern) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new FraudAssessmentRequestRejectedException(
                    fieldName + " has an invalid format");
        }
        return value;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new FraudAssessmentRequestRejectedException(fieldName + " must not be blank");
        }
        return value;
    }

    private static BigDecimal parseAmount(String amount) {
        if (amount == null || amount.isBlank()) {
            throw new FraudAssessmentRequestRejectedException("amount must not be blank");
        }
        BigDecimal parsedAmount;
        try {
            parsedAmount = new BigDecimal(amount);
        } catch (NumberFormatException e) {
            throw new FraudAssessmentRequestRejectedException("amount must be a valid decimal string");
        }
        if (parsedAmount.signum() <= 0) {
            throw new FraudAssessmentRequestRejectedException("amount must be positive");
        }

        long integerDigits = Math.max(
                (long) parsedAmount.precision() - parsedAmount.scale(),
                0L);
        int fractionDigits = Math.max(parsedAmount.scale(), 0);
        if (integerDigits > MAX_AMOUNT_INTEGER_DIGITS
                || fractionDigits > MAX_AMOUNT_FRACTION_DIGITS) {
            throw new FraudAssessmentRequestRejectedException(
                    "amount must have at most "
                            + MAX_AMOUNT_INTEGER_DIGITS
                            + " integer digits and "
                            + MAX_AMOUNT_FRACTION_DIGITS
                            + " fraction digits");
        }
        return parsedAmount;
    }

    private static FraudChannel toDomainChannel(TransactionChannel channel) {
        return switch (channel) {
            case TRANSACTION_CHANNEL_ECOMMERCE -> FraudChannel.ECOMMERCE;
            case TRANSACTION_CHANNEL_POINT_OF_SALE -> FraudChannel.POINT_OF_SALE;
            case TRANSACTION_CHANNEL_UNSPECIFIED, UNRECOGNIZED ->
                    throw new FraudAssessmentRequestRejectedException("channel must be specified");
        };
    }

    private static Instant toDomainTransactionTime(AssessFraudRequest request) {
        if (!request.hasTransactionTime()) {
            throw new FraudAssessmentRequestRejectedException("transactionTime must be specified");
        }
        Timestamp transactionTime = request.getTransactionTime();
        if (transactionTime.getSeconds() < PROTOBUF_TIMESTAMP_MIN_SECONDS
                || transactionTime.getSeconds() > PROTOBUF_TIMESTAMP_MAX_SECONDS
                || transactionTime.getNanos() < 0
                || transactionTime.getNanos() > PROTOBUF_TIMESTAMP_MAX_NANOS) {
            throw new FraudAssessmentRequestRejectedException(
                    "transactionTime must be a valid protobuf timestamp");
        }
        return Instant.ofEpochSecond(transactionTime.getSeconds(), transactionTime.getNanos());
    }

    private static FraudAssessmentOutcome toContractOutcome(FraudAssessment assessment) {
        return switch (assessment) {
            case CLEAR -> FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_CLEAR;
            case REVIEW -> FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_REVIEW;
            case HIGH_RISK -> FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_HIGH_RISK;
        };
    }

    private static RuleMatchSeverity toContractSeverity(FraudRuleSeverity severity) {
        return switch (severity) {
            case REVIEW -> RuleMatchSeverity.RULE_MATCH_SEVERITY_REVIEW;
            case HIGH_RISK -> RuleMatchSeverity.RULE_MATCH_SEVERITY_HIGH_RISK;
        };
    }
}
