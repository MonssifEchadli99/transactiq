package io.github.monssifechadli99.transactiq.case_management.support;

import com.google.protobuf.Timestamp;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.AuthorizationCompletedEvent;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventAuthorizationDecision;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventChannel;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventDeclineReason;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventFraudAssessment;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventFraudRuleMatch;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventFraudRuleSeverity;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventNonFraudResult;
import java.time.Instant;
import java.util.UUID;

public final class AuthorizationEventFixtures {

    public static final String CARD_FINGERPRINT =
            "eb70d1456f22db87b692e80b6429d1e3987909319c94f72a44f6a05d3e091655";

    private AuthorizationEventFixtures() {}

    public static AuthorizationCompletedEvent.Builder reviewEvent(UUID eventId, UUID requestId) {
        return baseEvent(eventId, requestId)
                .setDecision(EventAuthorizationDecision.EVENT_AUTHORIZATION_DECISION_APPROVED)
                .setFraudAssessment(EventFraudAssessment.EVENT_FRAUD_ASSESSMENT_REVIEW)
                .setRiskScore(15)
                .addMatchedRules(rule(
                        "MERCHANT_PROFILE",
                        EventFraudRuleSeverity.EVENT_FRAUD_RULE_SEVERITY_REVIEW,
                        "Synthetic merchant profile requires review",
                        15))
                .setCaseRequired(true);
    }

    public static AuthorizationCompletedEvent.Builder highRiskEvent(UUID eventId, UUID requestId) {
        return baseEvent(eventId, requestId)
                .setDecision(EventAuthorizationDecision.EVENT_AUTHORIZATION_DECISION_DECLINED)
                .setDeclineReason(EventDeclineReason.EVENT_DECLINE_REASON_HIGH_FRAUD_RISK)
                .setFraudAssessment(EventFraudAssessment.EVENT_FRAUD_ASSESSMENT_HIGH_RISK)
                .setRiskScore(80)
                .addMatchedRules(rule(
                        "COUNTRY_SWITCH",
                        EventFraudRuleSeverity.EVENT_FRAUD_RULE_SEVERITY_HIGH_RISK,
                        "Synthetic country changed inside the velocity window",
                        80))
                .setCaseRequired(true);
    }

    public static AuthorizationCompletedEvent.Builder clearEvent(UUID eventId, UUID requestId) {
        return baseEvent(eventId, requestId)
                .setDecision(EventAuthorizationDecision.EVENT_AUTHORIZATION_DECISION_APPROVED)
                .setFraudAssessment(EventFraudAssessment.EVENT_FRAUD_ASSESSMENT_CLEAR)
                .setRiskScore(0)
                .setCaseRequired(false);
    }

    public static AuthorizationCompletedEvent.Builder insufficientFundsReviewEvent(
            UUID eventId, UUID requestId) {
        return reviewEvent(eventId, requestId)
                .setNonFraudResult(
                        EventNonFraudResult.EVENT_NON_FRAUD_RESULT_INSUFFICIENT_FUNDS)
                .setDecision(EventAuthorizationDecision.EVENT_AUTHORIZATION_DECISION_DECLINED)
                .setDeclineReason(EventDeclineReason.EVENT_DECLINE_REASON_INSUFFICIENT_FUNDS);
    }

    private static AuthorizationCompletedEvent.Builder baseEvent(UUID eventId, UUID requestId) {
        return AuthorizationCompletedEvent.newBuilder()
                .setEventId(eventId.toString())
                .setOccurredAt(timestamp(Instant.parse("2026-07-30T10:15:31.123456789Z")))
                .setRequestId(requestId.toString())
                .setCardTokenFingerprint(CARD_FINGERPRINT)
                .setMerchantId("merchant-review")
                .setMerchantCategoryCode("5732")
                .setAmount("75")
                .setCurrency("EUR")
                .setCountry("DE")
                .setChannel(EventChannel.EVENT_CHANNEL_ECOMMERCE)
                .setTransactionTime(timestamp(Instant.parse("2026-07-30T10:15:30.987654321Z")))
                .setNonFraudResult(EventNonFraudResult.EVENT_NON_FRAUD_RESULT_PASSED);
    }

    private static EventFraudRuleMatch rule(
            String code,
            EventFraudRuleSeverity severity,
            String evidence,
            int contribution) {
        return EventFraudRuleMatch.newBuilder()
                .setRuleCode(code)
                .setSeverity(severity)
                .setEvidence(evidence)
                .setScoreContribution(contribution)
                .build();
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
