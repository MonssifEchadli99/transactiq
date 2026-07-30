package io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Timestamps;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.AuthorizationCompletedEvent;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventAuthorizationDecision;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventChannel;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventDeclineReason;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventFraudAssessment;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventFraudRuleMatch;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventFraudRuleSeverity;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventNonFraudResult;
import io.github.monssifechadli99.transactiq.case_management.domain.AuthorizationDecision;
import io.github.monssifechadli99.transactiq.case_management.domain.AuthorizationEventSnapshot;
import io.github.monssifechadli99.transactiq.case_management.domain.DeclineReason;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudAssessment;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudRuleSeverity;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudRuleSnapshot;
import io.github.monssifechadli99.transactiq.case_management.domain.InvalidAuthorizationEventException;
import io.github.monssifechadli99.transactiq.case_management.domain.NonFraudResult;
import io.github.monssifechadli99.transactiq.case_management.domain.TransactionChannel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public final class AuthorizationCompletedEventParser {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern MCC = Pattern.compile("[0-9]{4}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

    public AuthorizationEventSnapshot parse(byte[] value) {
        if (value == null || value.length == 0) {
            throw new InvalidAuthorizationEventException("Kafka value must not be empty");
        }

        AuthorizationCompletedEvent event;
        try {
            event = AuthorizationCompletedEvent.parseFrom(value);
        } catch (InvalidProtocolBufferException invalidPayload) {
            throw new InvalidAuthorizationEventException(
                    "Kafka value is not a valid authorization-completed event", invalidPayload);
        }

        UUID eventId = uuid(event.getEventId(), "eventId");
        UUID requestId = uuid(event.getRequestId(), "requestId");
        Instant occurredAt = timestamp(event.hasOccurredAt(), event.getOccurredAt(), "occurredAt");
        Instant transactionTime =
                timestamp(event.hasTransactionTime(), event.getTransactionTime(), "transactionTime");
        String cardFingerprint = matching(
                event.getCardTokenFingerprint(), SHA_256, "cardTokenFingerprint");
        String merchantId = text(event.getMerchantId(), "merchantId", 64);
        String merchantCategoryCode =
                matching(event.getMerchantCategoryCode(), MCC, "merchantCategoryCode");
        BigDecimal amount = amount(event.getAmount());
        String currency = matching(event.getCurrency(), CURRENCY, "currency");
        String country = matching(event.getCountry(), COUNTRY, "country");
        TransactionChannel channel = channel(event.getChannel());
        NonFraudResult nonFraudResult = nonFraudResult(event.getNonFraudResult());
        AuthorizationDecision decision = decision(event.getDecision());
        DeclineReason declineReason = event.hasDeclineReason()
                ? declineReason(event.getDeclineReason())
                : null;
        FraudAssessment fraudAssessment = fraudAssessment(event.getFraudAssessment());
        if (!event.hasRiskScore()) {
            throw new InvalidAuthorizationEventException("riskScore must be present");
        }
        int riskScore = event.getRiskScore();
        List<FraudRuleSnapshot> matchedRules = matchedRules(event.getMatchedRulesList());

        validateOutcome(nonFraudResult, decision, declineReason);
        validateFraudResult(fraudAssessment, riskScore, matchedRules);
        validateCaseRequirement(fraudAssessment, event.getCaseRequired());

        return new AuthorizationEventSnapshot(
                eventId,
                sha256(value),
                occurredAt,
                requestId,
                cardFingerprint,
                merchantId,
                merchantCategoryCode,
                amount,
                currency,
                country,
                channel,
                transactionTime,
                nonFraudResult,
                decision,
                declineReason,
                fraudAssessment,
                riskScore,
                matchedRules,
                event.getCaseRequired());
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalidUuid) {
            throw new InvalidAuthorizationEventException(field + " must be a UUID", invalidUuid);
        }
    }

    private static Instant timestamp(boolean present, Timestamp value, String field) {
        if (!present || !Timestamps.isValid(value)) {
            throw new InvalidAuthorizationEventException(field + " must be a valid timestamp");
        }
        return Instant.ofEpochSecond(value.getSeconds(), value.getNanos());
    }

    private static String text(String value, String field, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new InvalidAuthorizationEventException(
                    field + " must be non-blank and at most " + maximumLength + " characters");
        }
        return value;
    }

    private static String matching(String value, Pattern pattern, String field) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new InvalidAuthorizationEventException(
                    field + " does not match the authorization event contract");
        }
        return value;
    }

    private static BigDecimal amount(String value) {
        final BigDecimal amount;
        try {
            amount = new BigDecimal(value);
        } catch (NumberFormatException invalidAmount) {
            throw new InvalidAuthorizationEventException(
                    "amount must be an exact decimal string", invalidAmount);
        }
        if (amount.signum() <= 0 || amount.scale() > 2 || amount.precision() - amount.scale() > 12) {
            throw new InvalidAuthorizationEventException(
                    "amount must be positive with at most 12 integer digits and 2 fractional digits");
        }
        return amount.setScale(Math.max(0, amount.scale()), RoundingMode.UNNECESSARY);
    }

    private static TransactionChannel channel(EventChannel value) {
        return switch (value) {
            case EVENT_CHANNEL_ECOMMERCE -> TransactionChannel.ECOMMERCE;
            case EVENT_CHANNEL_POINT_OF_SALE -> TransactionChannel.POINT_OF_SALE;
            case EVENT_CHANNEL_UNSPECIFIED, UNRECOGNIZED ->
                    throw new InvalidAuthorizationEventException("channel must be specified");
        };
    }

    private static NonFraudResult nonFraudResult(EventNonFraudResult value) {
        return switch (value) {
            case EVENT_NON_FRAUD_RESULT_PASSED -> NonFraudResult.PASSED;
            case EVENT_NON_FRAUD_RESULT_INSUFFICIENT_FUNDS ->
                    NonFraudResult.INSUFFICIENT_FUNDS;
            case EVENT_NON_FRAUD_RESULT_UNSPECIFIED, UNRECOGNIZED ->
                    throw new InvalidAuthorizationEventException("nonFraudResult must be specified");
        };
    }

    private static AuthorizationDecision decision(EventAuthorizationDecision value) {
        return switch (value) {
            case EVENT_AUTHORIZATION_DECISION_APPROVED -> AuthorizationDecision.APPROVED;
            case EVENT_AUTHORIZATION_DECISION_DECLINED -> AuthorizationDecision.DECLINED;
            case EVENT_AUTHORIZATION_DECISION_UNSPECIFIED, UNRECOGNIZED ->
                    throw new InvalidAuthorizationEventException("decision must be specified");
        };
    }

    private static DeclineReason declineReason(EventDeclineReason value) {
        return switch (value) {
            case EVENT_DECLINE_REASON_INSUFFICIENT_FUNDS -> DeclineReason.INSUFFICIENT_FUNDS;
            case EVENT_DECLINE_REASON_HIGH_FRAUD_RISK -> DeclineReason.HIGH_FRAUD_RISK;
            case EVENT_DECLINE_REASON_FRAUD_REVIEW_REQUIRED ->
                    DeclineReason.FRAUD_REVIEW_REQUIRED;
            case EVENT_DECLINE_REASON_UNSPECIFIED, UNRECOGNIZED ->
                    throw new InvalidAuthorizationEventException("declineReason must be specified");
        };
    }

    private static FraudAssessment fraudAssessment(EventFraudAssessment value) {
        return switch (value) {
            case EVENT_FRAUD_ASSESSMENT_CLEAR -> FraudAssessment.CLEAR;
            case EVENT_FRAUD_ASSESSMENT_REVIEW -> FraudAssessment.REVIEW;
            case EVENT_FRAUD_ASSESSMENT_HIGH_RISK -> FraudAssessment.HIGH_RISK;
            case EVENT_FRAUD_ASSESSMENT_UNSPECIFIED, UNRECOGNIZED ->
                    throw new InvalidAuthorizationEventException(
                            "fraudAssessment must be specified");
        };
    }

    private static List<FraudRuleSnapshot> matchedRules(List<EventFraudRuleMatch> values) {
        List<FraudRuleSnapshot> matches = new ArrayList<>(values.size());
        for (EventFraudRuleMatch value : values) {
            if (!value.hasScoreContribution()) {
                throw new InvalidAuthorizationEventException(
                        "every matched rule scoreContribution must be present");
            }
            matches.add(new FraudRuleSnapshot(
                    text(value.getRuleCode(), "matchedRules.ruleCode", 128),
                    severity(value.getSeverity()),
                    text(value.getEvidence(), "matchedRules.evidence", Integer.MAX_VALUE),
                    value.getScoreContribution()));
        }
        return List.copyOf(matches);
    }

    private static FraudRuleSeverity severity(EventFraudRuleSeverity value) {
        return switch (value) {
            case EVENT_FRAUD_RULE_SEVERITY_REVIEW -> FraudRuleSeverity.REVIEW;
            case EVENT_FRAUD_RULE_SEVERITY_HIGH_RISK -> FraudRuleSeverity.HIGH_RISK;
            case EVENT_FRAUD_RULE_SEVERITY_UNSPECIFIED, UNRECOGNIZED ->
                    throw new InvalidAuthorizationEventException(
                            "matched rule severity must be specified");
        };
    }

    private static void validateOutcome(
            NonFraudResult nonFraudResult,
            AuthorizationDecision decision,
            DeclineReason declineReason) {
        if (decision == AuthorizationDecision.APPROVED && declineReason != null) {
            throw new InvalidAuthorizationEventException(
                    "approved authorization must not have a declineReason");
        }
        if (decision == AuthorizationDecision.DECLINED && declineReason == null) {
            throw new InvalidAuthorizationEventException(
                    "declined authorization must have a declineReason");
        }
        if (nonFraudResult == NonFraudResult.INSUFFICIENT_FUNDS
                && (decision != AuthorizationDecision.DECLINED
                        || declineReason != DeclineReason.INSUFFICIENT_FUNDS)) {
            throw new InvalidAuthorizationEventException(
                    "insufficient funds must remain the primary decline reason");
        }
        if (nonFraudResult == NonFraudResult.PASSED
                && declineReason == DeclineReason.INSUFFICIENT_FUNDS) {
            throw new InvalidAuthorizationEventException(
                    "passed non-fraud checks cannot decline for insufficient funds");
        }
    }

    private static void validateFraudResult(
            FraudAssessment assessment, int riskScore, List<FraudRuleSnapshot> matchedRules) {
        for (int index = 1; index < matchedRules.size(); index++) {
            if (matchedRules.get(index - 1).ruleCode()
                            .compareTo(matchedRules.get(index).ruleCode())
                    >= 0) {
                throw new InvalidAuthorizationEventException(
                        "matched rules must have unique codes in alphabetical order");
            }
        }
        int expectedScore = (int) Math.min(
                100L,
                matchedRules.stream()
                        .mapToLong(FraudRuleSnapshot::scoreContribution)
                        .sum());
        if (riskScore != expectedScore) {
            throw new InvalidAuthorizationEventException(
                    "riskScore must equal the capped matched-rule contribution sum");
        }
        FraudAssessment expectedAssessment = matchedRules.stream()
                .map(FraudRuleSnapshot::severity)
                .max(Comparator.naturalOrder())
                .map(severity -> severity == FraudRuleSeverity.HIGH_RISK
                        ? FraudAssessment.HIGH_RISK
                        : FraudAssessment.REVIEW)
                .orElse(FraudAssessment.CLEAR);
        if (assessment != expectedAssessment) {
            throw new InvalidAuthorizationEventException(
                    "fraudAssessment must equal the highest matched-rule severity");
        }
        boolean validBand = switch (assessment) {
            case CLEAR -> riskScore == 0;
            case REVIEW -> riskScore >= 1 && riskScore <= 69;
            case HIGH_RISK -> riskScore >= 70 && riskScore <= 100;
        };
        if (!validBand) {
            throw new InvalidAuthorizationEventException(
                    "riskScore is inconsistent with fraudAssessment");
        }
    }

    private static void validateCaseRequirement(
            FraudAssessment assessment, boolean caseRequired) {
        if (caseRequired != (assessment != FraudAssessment.CLEAR)) {
            throw new InvalidAuthorizationEventException(
                    "caseRequired is inconsistent with fraudAssessment");
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 must be available", unavailable);
        }
    }
}
