package io.github.monssifechadli99.transactiq.authorization.adapter.out.event;

import com.google.protobuf.Timestamp;
import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationChannel;
import io.github.monssifechadli99.transactiq.authorization.application.model.SerializedAuthorizationCompletedEvent;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationDecision;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationOutcome;
import io.github.monssifechadli99.transactiq.authorization.domain.DeclineReason;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessment;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessmentResult;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudRuleMatch;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudRuleSeverity;
import io.github.monssifechadli99.transactiq.authorization.domain.NonFraudCheckResult;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.AuthorizationCompletedEvent;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventAuthorizationDecision;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventChannel;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventDeclineReason;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventFraudAssessment;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventFraudRuleMatch;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventFraudRuleSeverity;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventNonFraudResult;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class AuthorizationCompletedEventMapper {

    private final Clock clock;
    private final Supplier<UUID> eventIdSupplier;

    public AuthorizationCompletedEventMapper(Clock clock, Supplier<UUID> eventIdSupplier) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.eventIdSupplier = Objects.requireNonNull(
                eventIdSupplier, "eventIdSupplier must not be null");
    }

    public SerializedAuthorizationCompletedEvent map(
            AuthorizationCommand command,
            FraudAssessmentResult fraudAssessment,
            NonFraudCheckResult nonFraudCheckResult,
            AuthorizationOutcome outcome) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(fraudAssessment, "fraudAssessment must not be null");
        Objects.requireNonNull(nonFraudCheckResult, "nonFraudCheckResult must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");

        UUID eventId = Objects.requireNonNull(
                eventIdSupplier.get(), "eventIdSupplier must not return null");
        Instant occurredAt = clock.instant();
        String cardTokenFingerprint = CardTokenFingerprint.sha256(command.cardToken());

        AuthorizationCompletedEvent.Builder event = AuthorizationCompletedEvent.newBuilder()
                .setEventId(eventId.toString())
                .setOccurredAt(timestamp(occurredAt))
                .setRequestId(command.requestId().toString())
                .setCardTokenFingerprint(cardTokenFingerprint)
                .setMerchantId(command.merchantId())
                .setMerchantCategoryCode(command.merchantCategoryCode())
                .setAmount(command.amount().stripTrailingZeros().toPlainString())
                .setCurrency(command.currency())
                .setCountry(command.country())
                .setChannel(channel(command.channel()))
                .setTransactionTime(timestamp(command.transactionTime()))
                .setNonFraudResult(nonFraudResult(nonFraudCheckResult))
                .setDecision(decision(outcome.decision()))
                .setFraudAssessment(fraudAssessment(fraudAssessment.assessment()))
                .setRiskScore(fraudAssessment.riskScore())
                .setCaseRequired(outcome.fraudCaseRequired());

        if (outcome instanceof AuthorizationOutcome.Declined declined) {
            event.setDeclineReason(declineReason(declined.declineReason()));
        }
        fraudAssessment.matchedRules().stream()
                .map(AuthorizationCompletedEventMapper::matchedRule)
                .forEach(event::addMatchedRules);

        byte[] payload = event.build().toByteArray();
        return new SerializedAuthorizationCompletedEvent(
                eventId, command.requestId(), occurredAt, cardTokenFingerprint, payload);
    }

    private static Timestamp timestamp(Instant instant) {
        Objects.requireNonNull(instant, "instant must not be null");
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static EventChannel channel(AuthorizationChannel channel) {
        return switch (channel) {
            case ECOMMERCE -> EventChannel.EVENT_CHANNEL_ECOMMERCE;
            case POINT_OF_SALE -> EventChannel.EVENT_CHANNEL_POINT_OF_SALE;
        };
    }

    private static EventNonFraudResult nonFraudResult(NonFraudCheckResult result) {
        return switch (result) {
            case PASSED -> EventNonFraudResult.EVENT_NON_FRAUD_RESULT_PASSED;
            case INSUFFICIENT_FUNDS ->
                    EventNonFraudResult.EVENT_NON_FRAUD_RESULT_INSUFFICIENT_FUNDS;
        };
    }

    private static EventAuthorizationDecision decision(AuthorizationDecision decision) {
        return switch (decision) {
            case APPROVED -> EventAuthorizationDecision.EVENT_AUTHORIZATION_DECISION_APPROVED;
            case DECLINED -> EventAuthorizationDecision.EVENT_AUTHORIZATION_DECISION_DECLINED;
        };
    }

    private static EventDeclineReason declineReason(DeclineReason reason) {
        return switch (reason) {
            case INSUFFICIENT_FUNDS -> EventDeclineReason.EVENT_DECLINE_REASON_INSUFFICIENT_FUNDS;
            case HIGH_FRAUD_RISK -> EventDeclineReason.EVENT_DECLINE_REASON_HIGH_FRAUD_RISK;
            case FRAUD_REVIEW_REQUIRED ->
                    EventDeclineReason.EVENT_DECLINE_REASON_FRAUD_REVIEW_REQUIRED;
        };
    }

    private static EventFraudAssessment fraudAssessment(FraudAssessment assessment) {
        return switch (assessment) {
            case CLEAR -> EventFraudAssessment.EVENT_FRAUD_ASSESSMENT_CLEAR;
            case REVIEW -> EventFraudAssessment.EVENT_FRAUD_ASSESSMENT_REVIEW;
            case HIGH_RISK -> EventFraudAssessment.EVENT_FRAUD_ASSESSMENT_HIGH_RISK;
        };
    }

    private static EventFraudRuleMatch matchedRule(FraudRuleMatch match) {
        return EventFraudRuleMatch.newBuilder()
                .setRuleCode(match.ruleCode())
                .setSeverity(severity(match.severity()))
                .setEvidence(match.evidence())
                .setScoreContribution(match.scoreContribution())
                .build();
    }

    private static EventFraudRuleSeverity severity(FraudRuleSeverity severity) {
        return switch (severity) {
            case REVIEW -> EventFraudRuleSeverity.EVENT_FRAUD_RULE_SEVERITY_REVIEW;
            case HIGH_RISK -> EventFraudRuleSeverity.EVENT_FRAUD_RULE_SEVERITY_HIGH_RISK;
        };
    }
}
