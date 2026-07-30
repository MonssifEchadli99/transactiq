package io.github.monssifechadli99.transactiq.authorization.event.contract.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Descriptors.FieldDescriptor;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AuthorizationCompletedEventContractTest {

    @Test
    void v1FieldNamesAndNumbersAreFixed() {
        Map<String, Integer> fields = AuthorizationCompletedEvent.getDescriptor()
                .getFields()
                .stream()
                .collect(Collectors.toMap(
                        FieldDescriptor::getName,
                        FieldDescriptor::getNumber));

        assertEquals(Map.ofEntries(
                Map.entry("event_id", 1),
                Map.entry("occurred_at", 2),
                Map.entry("request_id", 3),
                Map.entry("card_token_fingerprint", 4),
                Map.entry("merchant_id", 5),
                Map.entry("merchant_category_code", 6),
                Map.entry("amount", 7),
                Map.entry("currency", 8),
                Map.entry("country", 9),
                Map.entry("channel", 10),
                Map.entry("transaction_time", 11),
                Map.entry("non_fraud_result", 12),
                Map.entry("decision", 13),
                Map.entry("decline_reason", 14),
                Map.entry("fraud_assessment", 15),
                Map.entry("risk_score", 16),
                Map.entry("matched_rules", 17),
                Map.entry("case_required", 18)), fields);
    }

    @Test
    void scoreAndContributionHaveExplicitPresence() {
        AuthorizationCompletedEvent event = AuthorizationCompletedEvent.newBuilder()
                .setRiskScore(0)
                .addMatchedRules(EventFraudRuleMatch.newBuilder()
                        .setScoreContribution(15)
                        .build())
                .build();

        assertTrue(event.hasRiskScore());
        assertTrue(event.getMatchedRules(0).hasScoreContribution());
    }

    @Test
    void everyContractEnumDefinesAnUnspecifiedZeroValue() {
        assertEquals(0, EventChannel.EVENT_CHANNEL_UNSPECIFIED.getNumber());
        assertEquals(0, EventNonFraudResult.EVENT_NON_FRAUD_RESULT_UNSPECIFIED.getNumber());
        assertEquals(0, EventAuthorizationDecision.EVENT_AUTHORIZATION_DECISION_UNSPECIFIED.getNumber());
        assertEquals(0, EventDeclineReason.EVENT_DECLINE_REASON_UNSPECIFIED.getNumber());
        assertEquals(0, EventFraudAssessment.EVENT_FRAUD_ASSESSMENT_UNSPECIFIED.getNumber());
        assertEquals(0, EventFraudRuleSeverity.EVENT_FRAUD_RULE_SEVERITY_UNSPECIFIED.getNumber());
    }
}
