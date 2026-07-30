package io.github.monssifechadli99.transactiq.authorization.adapter.out.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Timestamp;
import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationChannel;
import io.github.monssifechadli99.transactiq.authorization.application.model.SerializedAuthorizationCompletedEvent;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
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
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventFraudRuleSeverity;
import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventNonFraudResult;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthorizationCompletedEventMapperTest {

    private static final UUID EVENT_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID REQUEST_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-22T09:10:11.123456789Z");
    private static final Instant TRANSACTION_TIME = Instant.parse("2026-07-22T09:09:30.987654321Z");
    private static final String CARD_TOKEN = "tok_synthetic_123";

    private final AuthorizationCompletedEventMapper mapper = new AuthorizationCompletedEventMapper(
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC), () -> EVENT_ID);

    @Test
    void mapsEveryClearApprovalFieldWithExplicitZeroScoreAndNoRawCardToken() throws Exception {
        SerializedAuthorizationCompletedEvent serialized = mapper.map(
                command(),
                FraudAssessmentResult.clear(),
                NonFraudCheckResult.PASSED,
                new AuthorizationOutcome.Approved(false));

        AuthorizationCompletedEvent event = AuthorizationCompletedEvent.parseFrom(serialized.payload());

        assertEquals(EVENT_ID, serialized.eventId());
        assertEquals(REQUEST_ID, serialized.requestId());
        assertEquals(OCCURRED_AT, serialized.occurredAt());
        assertEquals(sha256(CARD_TOKEN), serialized.partitionKey());
        assertEquals(EVENT_ID.toString(), event.getEventId());
        assertEquals(timestamp(OCCURRED_AT), event.getOccurredAt());
        assertEquals(REQUEST_ID.toString(), event.getRequestId());
        assertEquals(sha256(CARD_TOKEN), event.getCardTokenFingerprint());
        assertEquals("merchant_synthetic_42", event.getMerchantId());
        assertEquals("5411", event.getMerchantCategoryCode());
        assertEquals("42.5", event.getAmount());
        assertEquals("EUR", event.getCurrency());
        assertEquals("DE", event.getCountry());
        assertEquals(EventChannel.EVENT_CHANNEL_ECOMMERCE, event.getChannel());
        assertEquals(timestamp(TRANSACTION_TIME), event.getTransactionTime());
        assertEquals(EventNonFraudResult.EVENT_NON_FRAUD_RESULT_PASSED, event.getNonFraudResult());
        assertEquals(
                EventAuthorizationDecision.EVENT_AUTHORIZATION_DECISION_APPROVED,
                event.getDecision());
        assertFalse(event.hasDeclineReason());
        assertEquals(EventFraudAssessment.EVENT_FRAUD_ASSESSMENT_CLEAR, event.getFraudAssessment());
        assertTrue(event.hasRiskScore());
        assertEquals(0, event.getRiskScore());
        assertEquals(0, event.getMatchedRulesCount());
        assertFalse(event.getCaseRequired());
        assertFalse(
                new String(serialized.payload(), StandardCharsets.ISO_8859_1).contains(CARD_TOKEN));
    }

    @Test
    void reviewWithInsufficientFundsKeepsFraudEvidenceAndRequiresCase() throws Exception {
        FraudAssessmentResult review = new FraudAssessmentResult(
                FraudAssessment.REVIEW,
                25,
                List.of(new FraudRuleMatch(
                        "NEW_MERCHANT",
                        FraudRuleSeverity.REVIEW,
                        "synthetic card first seen at merchant",
                        25)));

        AuthorizationCompletedEvent event = AuthorizationCompletedEvent.parseFrom(mapper.map(
                        command(),
                        review,
                        NonFraudCheckResult.INSUFFICIENT_FUNDS,
                        new AuthorizationOutcome.Declined(DeclineReason.INSUFFICIENT_FUNDS, true))
                .payload());

        assertEquals(
                EventNonFraudResult.EVENT_NON_FRAUD_RESULT_INSUFFICIENT_FUNDS,
                event.getNonFraudResult());
        assertEquals(
                EventAuthorizationDecision.EVENT_AUTHORIZATION_DECISION_DECLINED,
                event.getDecision());
        assertTrue(event.hasDeclineReason());
        assertEquals(
                EventDeclineReason.EVENT_DECLINE_REASON_INSUFFICIENT_FUNDS,
                event.getDeclineReason());
        assertEquals(EventFraudAssessment.EVENT_FRAUD_ASSESSMENT_REVIEW, event.getFraudAssessment());
        assertEquals(25, event.getRiskScore());
        assertTrue(event.getCaseRequired());
        assertEquals("NEW_MERCHANT", event.getMatchedRules(0).getRuleCode());
        assertEquals(
                EventFraudRuleSeverity.EVENT_FRAUD_RULE_SEVERITY_REVIEW,
                event.getMatchedRules(0).getSeverity());
        assertEquals("synthetic card first seen at merchant", event.getMatchedRules(0).getEvidence());
        assertTrue(event.getMatchedRules(0).hasScoreContribution());
        assertEquals(25, event.getMatchedRules(0).getScoreContribution());
    }

    @Test
    void highRiskPreservesAlphabeticalRuleOrderAndContributions() throws Exception {
        FraudAssessmentResult highRisk = new FraudAssessmentResult(
                FraudAssessment.HIGH_RISK,
                100,
                List.of(
                        new FraudRuleMatch(
                                "AMOUNT_SPIKE",
                                FraudRuleSeverity.HIGH_RISK,
                                "synthetic amount exceeds threshold",
                                80),
                        new FraudRuleMatch(
                                "COUNTRY_MISMATCH",
                                FraudRuleSeverity.REVIEW,
                                "synthetic countries differ",
                                30)));

        AuthorizationCompletedEvent event = AuthorizationCompletedEvent.parseFrom(mapper.map(
                        command(),
                        highRisk,
                        NonFraudCheckResult.PASSED,
                        new AuthorizationOutcome.Declined(DeclineReason.HIGH_FRAUD_RISK, true))
                .payload());

        assertEquals(EventFraudAssessment.EVENT_FRAUD_ASSESSMENT_HIGH_RISK, event.getFraudAssessment());
        assertEquals(100, event.getRiskScore());
        assertEquals(
                List.of("AMOUNT_SPIKE", "COUNTRY_MISMATCH"),
                event.getMatchedRulesList().stream().map(match -> match.getRuleCode()).toList());
        assertEquals(List.of(80, 30), event.getMatchedRulesList().stream()
                .map(match -> match.getScoreContribution())
                .toList());
        assertTrue(event.getCaseRequired());
    }

    private static AuthorizationCommand command() {
        return new AuthorizationCommand(
                REQUEST_ID,
                CARD_TOKEN,
                "merchant_synthetic_42",
                "5411",
                new BigDecimal("42.5000"),
                "EUR",
                "DE",
                AuthorizationChannel.ECOMMERCE,
                TRANSACTION_TIME);
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static String sha256(String value) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
