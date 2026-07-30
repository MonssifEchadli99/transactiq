package io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka;

import static io.github.monssifechadli99.transactiq.case_management.support.AuthorizationEventFixtures.clearEvent;
import static io.github.monssifechadli99.transactiq.case_management.support.AuthorizationEventFixtures.insufficientFundsReviewEvent;
import static io.github.monssifechadli99.transactiq.case_management.support.AuthorizationEventFixtures.reviewEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.authorization.event.contract.v1.EventFraudAssessment;
import io.github.monssifechadli99.transactiq.case_management.domain.AuthorizationDecision;
import io.github.monssifechadli99.transactiq.case_management.domain.DeclineReason;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudAssessment;
import io.github.monssifechadli99.transactiq.case_management.domain.InvalidAuthorizationEventException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthorizationCompletedEventParserTest {

    private final AuthorizationCompletedEventParser parser =
            new AuthorizationCompletedEventParser();

    @Test
    void parsesCompleteReviewSnapshotAndHashesExactReceivedBytes() {
        byte[] bytes = reviewEvent(
                        UUID.fromString("10000000-0000-4000-8000-000000000001"),
                        UUID.fromString("20000000-0000-4000-8000-000000000001"))
                .build()
                .toByteArray();

        var snapshot = parser.parse(bytes);

        assertEquals(FraudAssessment.REVIEW, snapshot.fraudAssessment());
        assertEquals(15, snapshot.riskScore());
        assertEquals(1, snapshot.matchedRules().size());
        assertEquals("MERCHANT_PROFILE", snapshot.matchedRules().getFirst().ruleCode());
        assertEquals(sha256(bytes), snapshot.sourceEventHash());
        assertTrue(snapshot.caseRequired());
    }

    @Test
    void parsesClearAsValidNonCaseEvent() {
        var snapshot = parser.parse(clearEvent(UUID.randomUUID(), UUID.randomUUID())
                .build()
                .toByteArray());

        assertEquals(FraudAssessment.CLEAR, snapshot.fraudAssessment());
        assertFalse(snapshot.caseRequired());
    }

    @Test
    void preservesInsufficientFundsAsPrimaryReasonWithReviewEvidence() {
        var snapshot = parser.parse(insufficientFundsReviewEvent(
                        UUID.randomUUID(), UUID.randomUUID())
                .build()
                .toByteArray());

        assertEquals(AuthorizationDecision.DECLINED, snapshot.decision());
        assertEquals(DeclineReason.INSUFFICIENT_FUNDS, snapshot.declineReason());
        assertEquals(FraudAssessment.REVIEW, snapshot.fraudAssessment());
        assertEquals(1, snapshot.matchedRules().size());
    }

    @Test
    void rejectsClearThatRequiresCase() {
        byte[] bytes = clearEvent(UUID.randomUUID(), UUID.randomUUID())
                .setCaseRequired(true)
                .build()
                .toByteArray();

        assertThrows(InvalidAuthorizationEventException.class, () -> parser.parse(bytes));
    }

    @Test
    void rejectsReviewThatDoesNotRequireCase() {
        byte[] bytes = reviewEvent(UUID.randomUUID(), UUID.randomUUID())
                .setCaseRequired(false)
                .build()
                .toByteArray();

        assertThrows(InvalidAuthorizationEventException.class, () -> parser.parse(bytes));
    }

    @Test
    void rejectsAssessmentThatDoesNotMatchEvidence() {
        byte[] bytes = reviewEvent(UUID.randomUUID(), UUID.randomUUID())
                .setFraudAssessment(EventFraudAssessment.EVENT_FRAUD_ASSESSMENT_HIGH_RISK)
                .build()
                .toByteArray();

        assertThrows(InvalidAuthorizationEventException.class, () -> parser.parse(bytes));
    }

    @Test
    void rejectsMalformedPayload() {
        assertThrows(
                InvalidAuthorizationEventException.class,
                () -> parser.parse(new byte[] {1, 2, 3}));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new AssertionError(unavailable);
        }
    }
}
