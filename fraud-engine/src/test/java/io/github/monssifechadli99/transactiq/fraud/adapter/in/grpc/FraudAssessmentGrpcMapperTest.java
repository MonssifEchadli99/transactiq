package io.github.monssifechadli99.transactiq.fraud.adapter.in.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.github.monssifechadli99.transactiq.fraud.domain.ScoredFraudRuleMatch;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class FraudAssessmentGrpcMapperTest {

    private static final UUID REQUEST_ID = UUID.fromString("f2b1c9d0-6e3a-4c1b-9b7a-2b6a1e9c7d44");
    private static final Instant TRANSACTION_TIME = Instant.parse("2026-07-19T10:15:30.123456789Z");

    private final FraudAssessmentGrpcMapper mapper = new FraudAssessmentGrpcMapper();

    @Test
    void mapsEveryRequestField() {
        AssessFraudRequest request = validRequestBuilder().build();

        FraudAssessmentRequest domainRequest = mapper.toDomainRequest(request);

        assertEquals(REQUEST_ID, domainRequest.requestId());
        assertEquals("tok_A1B2C3D4", domainRequest.cardToken());
        assertEquals("merchant-123", domainRequest.merchantId());
        assertEquals("5732", domainRequest.merchantCategoryCode());
        assertEquals(new BigDecimal("1200.00"), domainRequest.amount());
        assertEquals("EUR", domainRequest.currency());
        assertEquals("DE", domainRequest.country());
        assertEquals(FraudChannel.ECOMMERCE, domainRequest.channel());
        assertEquals(TRANSACTION_TIME, domainRequest.transactionTime());
    }

    @Test
    void preservesExactDecimalAmountWithoutFloatingPointLoss() {
        AssessFraudRequest request = validRequestBuilder()
                .setAmount("123456789012.34")
                .build();

        FraudAssessmentRequest domainRequest = mapper.toDomainRequest(request);

        assertEquals(new BigDecimal("123456789012.34"), domainRequest.amount());
    }

    @Test
    void preservesExactNanosecondTimestamp() {
        AssessFraudRequest request = validRequestBuilder()
                .setTransactionTime(Timestamp.newBuilder().setSeconds(1_800_000_000L).setNanos(987_654_321).build())
                .build();

        FraudAssessmentRequest domainRequest = mapper.toDomainRequest(request);

        assertEquals(Instant.ofEpochSecond(1_800_000_000L, 987_654_321), domainRequest.transactionTime());
    }

    @Test
    void rejectsBlankRequestId() {
        assertRejected(validRequestBuilder().setRequestId("").build());
    }

    @Test
    void rejectsMalformedRequestIdUuid() {
        assertRejected(validRequestBuilder().setRequestId("not-a-uuid").build());
    }

    @Test
    void rejectsBlankCardToken() {
        assertRejected(validRequestBuilder().setCardToken(" ").build());
    }

    @ParameterizedTest(name = "rejects invalid card token: {0}")
    @MethodSource("invalidCardTokens")
    void rejectsInvalidCardToken(String cardToken) {
        assertRejected(validRequestBuilder().setCardToken(cardToken).build());
    }

    @Test
    void doesNotIncludeInvalidCardTokenInRejectionMessage() {
        String cardToken = "tok_sensitive!";

        FraudAssessmentRequestRejectedException exception =
                assertRejected(validRequestBuilder().setCardToken(cardToken).build());

        assertFalse(exception.getMessage().contains(cardToken));
    }

    @Test
    void rejectsBlankMerchantId() {
        assertRejected(validRequestBuilder().setMerchantId("").build());
    }

    @Test
    void rejectsMerchantIdLongerThanHttpLimit() {
        assertRejected(validRequestBuilder().setMerchantId("m".repeat(65)).build());
    }

    @Test
    void rejectsBlankMerchantCategoryCode() {
        assertRejected(validRequestBuilder().setMerchantCategoryCode("").build());
    }

    @ParameterizedTest(name = "rejects invalid MCC: {0}")
    @ValueSource(strings = {"541", "54111", "54A1"})
    void rejectsInvalidMerchantCategoryCode(String merchantCategoryCode) {
        assertRejected(validRequestBuilder()
                .setMerchantCategoryCode(merchantCategoryCode)
                .build());
    }

    @Test
    void rejectsBlankAmount() {
        assertRejected(validRequestBuilder().setAmount("").build());
    }

    @Test
    void rejectsMalformedAmount() {
        assertRejected(validRequestBuilder().setAmount("not-a-decimal").build());
    }

    @ParameterizedTest(name = "rejects amount outside HTTP constraints: {0}")
    @ValueSource(strings = {"0", "-0.01", "1.001", "1000000000000.00"})
    void rejectsAmountOutsideHttpConstraints(String amount) {
        assertRejected(validRequestBuilder().setAmount(amount).build());
    }

    @Test
    void rejectsBlankCurrency() {
        assertRejected(validRequestBuilder().setCurrency("").build());
    }

    @ParameterizedTest(name = "rejects invalid currency: {0}")
    @ValueSource(strings = {"eur", "EU", "EURO", "E1R"})
    void rejectsInvalidCurrency(String currency) {
        assertRejected(validRequestBuilder().setCurrency(currency).build());
    }

    @Test
    void rejectsBlankCountry() {
        assertRejected(validRequestBuilder().setCountry("").build());
    }

    @ParameterizedTest(name = "rejects invalid country: {0}")
    @ValueSource(strings = {"de", "D", "DEU", "D1"})
    void rejectsInvalidCountry(String country) {
        assertRejected(validRequestBuilder().setCountry(country).build());
    }

    @Test
    void rejectsUnspecifiedChannel() {
        assertRejected(validRequestBuilder()
                .setChannel(TransactionChannel.TRANSACTION_CHANNEL_UNSPECIFIED)
                .build());
    }

    @Test
    void rejectsMissingTransactionTime() {
        AssessFraudRequest request = AssessFraudRequest.newBuilder()
                .setRequestId(REQUEST_ID.toString())
                .setCardToken("tok_A1B2C3D4")
                .setMerchantId("merchant-123")
                .setMerchantCategoryCode("5732")
                .setAmount("1200.00")
                .setCurrency("EUR")
                .setCountry("DE")
                .setChannel(TransactionChannel.TRANSACTION_CHANNEL_ECOMMERCE)
                .build();

        assertRejected(request);
    }

    @ParameterizedTest
    @MethodSource("invalidTransactionTimes")
    void rejectsInvalidProtobufTimestamp(Timestamp transactionTime) {
        assertRejected(validRequestBuilder()
                .setTransactionTime(transactionTime)
                .build());
    }

    @Test
    void mapsClearResultWithNoMatchedRules() {
        FraudAssessmentResult result = new FraudAssessmentResult(
                FraudAssessment.CLEAR, 0, List.of());

        AssessFraudResponse response = mapper.toContractResponse(result);

        assertEquals(FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_CLEAR, response.getAssessment());
        assertTrue(response.hasRiskScore());
        assertEquals(0, response.getRiskScore());
        assertTrue(response.getMatchedRulesList().isEmpty());
    }

    @Test
    void mapsResultWithMatchedRulesOfBothSeverities() {
        ScoredFraudRuleMatch reviewRule = new ScoredFraudRuleMatch(
                "MERCHANT_PROFILE", FraudRuleSeverity.REVIEW,
                "merchant merchant-review has synthetic REVIEW profile", 15);
        ScoredFraudRuleMatch highRiskRule = new ScoredFraudRuleMatch(
                "AMOUNT_THRESHOLD", FraudRuleSeverity.HIGH_RISK,
                "amount EUR 2600.00 met synthetic HIGH_RISK threshold 2500.00", 70);
        FraudAssessmentResult result = new FraudAssessmentResult(
                FraudAssessment.HIGH_RISK, 85, List.of(highRiskRule, reviewRule));

        AssessFraudResponse response = mapper.toContractResponse(result);

        assertEquals(FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_HIGH_RISK, response.getAssessment());
        assertTrue(response.hasRiskScore());
        assertEquals(85, response.getRiskScore());
        assertEquals(2, response.getMatchedRulesCount());

        RuleMatch mappedHighRiskRule = response.getMatchedRules(0);
        assertEquals("AMOUNT_THRESHOLD", mappedHighRiskRule.getRuleCode());
        assertEquals(RuleMatchSeverity.RULE_MATCH_SEVERITY_HIGH_RISK, mappedHighRiskRule.getSeverity());
        assertEquals(
                "amount EUR 2600.00 met synthetic HIGH_RISK threshold 2500.00",
                mappedHighRiskRule.getEvidence());
        assertTrue(mappedHighRiskRule.hasScoreContribution());
        assertEquals(70, mappedHighRiskRule.getScoreContribution());

        RuleMatch mappedReviewRule = response.getMatchedRules(1);
        assertEquals("MERCHANT_PROFILE", mappedReviewRule.getRuleCode());
        assertEquals(RuleMatchSeverity.RULE_MATCH_SEVERITY_REVIEW, mappedReviewRule.getSeverity());
        assertEquals(
                "merchant merchant-review has synthetic REVIEW profile",
                mappedReviewRule.getEvidence());
        assertTrue(mappedReviewRule.hasScoreContribution());
        assertEquals(15, mappedReviewRule.getScoreContribution());
    }

    private FraudAssessmentRequestRejectedException assertRejected(AssessFraudRequest request) {
        return assertThrows(
                FraudAssessmentRequestRejectedException.class,
                () -> mapper.toDomainRequest(request));
    }

    private static Stream<String> invalidCardTokens() {
        return Stream.of(
                "card_A1B2C3D4",
                "tok_1234567",
                "tok_1234567!",
                "tok_" + "A".repeat(61));
    }

    private static Stream<Timestamp> invalidTransactionTimes() {
        return Stream.of(
                Timestamp.newBuilder()
                        .setSeconds(-62_135_596_801L)
                        .build(),
                Timestamp.newBuilder()
                        .setSeconds(253_402_300_800L)
                        .build(),
                Timestamp.newBuilder()
                        .setNanos(-1)
                        .build(),
                Timestamp.newBuilder()
                        .setNanos(1_000_000_000)
                        .build());
    }

    private static AssessFraudRequest.Builder validRequestBuilder() {
        return AssessFraudRequest.newBuilder()
                .setRequestId(REQUEST_ID.toString())
                .setCardToken("tok_A1B2C3D4")
                .setMerchantId("merchant-123")
                .setMerchantCategoryCode("5732")
                .setAmount("1200.00")
                .setCurrency("EUR")
                .setCountry("DE")
                .setChannel(TransactionChannel.TRANSACTION_CHANNEL_ECOMMERCE)
                .setTransactionTime(Timestamp.newBuilder()
                        .setSeconds(TRANSACTION_TIME.getEpochSecond())
                        .setNanos(TRANSACTION_TIME.getNano())
                        .build());
    }
}
