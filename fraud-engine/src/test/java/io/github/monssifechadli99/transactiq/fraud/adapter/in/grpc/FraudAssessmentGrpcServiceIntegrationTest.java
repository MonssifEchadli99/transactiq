package io.github.monssifechadli99.transactiq.fraud.adapter.in.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Timestamp;
import io.github.monssifechadli99.transactiq.fraud.configuration.GrpcServerLifecycle;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.AssessFraudRequest;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.AssessFraudResponse;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.FraudAssessmentOutcome;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.FraudAssessmentServiceGrpc;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.RuleMatchSeverity;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.TransactionChannel;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = "fraud.grpc.port=0")
@Testcontainers
class FraudAssessmentGrpcServiceIntegrationTest {

    private static final UUID REQUEST_ID = UUID.fromString("f2b1c9d0-6e3a-4c1b-9b7a-2b6a1e9c7d44");
    private static final int REDIS_PORT = 6379;

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4.9-alpine3.21"))
            .withExposedPorts(REDIS_PORT);

    @Autowired
    private GrpcServerLifecycle grpcServerLifecycle;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private ManagedChannel channel;
    private FraudAssessmentServiceGrpc.FraudAssessmentServiceBlockingStub stub;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
    }

    @BeforeEach
    void connectToRealGrpcServer() {
        try (var connection = Objects.requireNonNull(redisTemplate.getConnectionFactory()).getConnection()) {
            connection.serverCommands().flushAll();
        }
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", grpcServerLifecycle.port())
                .usePlaintext()
                .build();
        stub = FraudAssessmentServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void closeChannel() {
        channel.shutdownNow();
    }

    @Test
    void clearResponseTravelsThroughRealGrpcTransport() {
        AssessFraudResponse response = stub.assess(validRequestBuilder().build());

        assertEquals(FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_CLEAR, response.getAssessment());
        assertTrue(response.hasRiskScore());
        assertEquals(0, response.getRiskScore());
        assertTrue(response.getMatchedRulesList().isEmpty());
    }

    @Test
    void reviewResponseTravelsThroughRealGrpcTransport() {
        AssessFraudResponse response = stub.assess(validRequestBuilder()
                .setMerchantId("merchant-review")
                .build());

        assertEquals(FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_REVIEW, response.getAssessment());
        assertTrue(response.hasRiskScore());
        assertEquals(15, response.getRiskScore());
        assertEquals(1, response.getMatchedRulesCount());
        assertEquals("MERCHANT_PROFILE", response.getMatchedRules(0).getRuleCode());
        assertEquals(
                RuleMatchSeverity.RULE_MATCH_SEVERITY_REVIEW,
                response.getMatchedRules(0).getSeverity());
        assertTrue(response.getMatchedRules(0).hasScoreContribution());
        assertEquals(15, response.getMatchedRules(0).getScoreContribution());
    }

    @Test
    void highRiskResponseTravelsThroughRealGrpcTransport() {
        AssessFraudResponse response = stub.assess(validRequestBuilder()
                .setMerchantId("merchant-high-risk")
                .build());

        assertEquals(FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_HIGH_RISK, response.getAssessment());
        assertTrue(response.hasRiskScore());
        assertEquals(75, response.getRiskScore());
        assertEquals(1, response.getMatchedRulesCount());
        assertEquals("MERCHANT_PROFILE", response.getMatchedRules(0).getRuleCode());
        assertEquals(
                RuleMatchSeverity.RULE_MATCH_SEVERITY_HIGH_RISK,
                response.getMatchedRules(0).getSeverity());
        assertTrue(response.getMatchedRules(0).hasScoreContribution());
        assertEquals(75, response.getMatchedRules(0).getScoreContribution());
    }

    @Test
    void fifthUniqueAttemptProducesVelocityReviewThroughRealGrpcTransport() {
        AssessFraudResponse response = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            response = stub.assess(validRequestBuilder()
                    .setRequestId(deterministicRequestId("velocity-review-" + attempt).toString())
                    .setCardToken("tok_velocityReview01")
                    .build());
        }

        assertEquals(FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_REVIEW, response.getAssessment());
        assertEquals(10, response.getRiskScore());
        assertEquals("TRANSACTION_COUNT", response.getMatchedRules(0).getRuleCode());
        assertEquals(
                "5 attempts in synthetic 60-second window met REVIEW threshold 5",
                response.getMatchedRules(0).getEvidence());
    }

    @Test
    void tenthUniqueAttemptProducesVelocityHighRiskThroughRealGrpcTransport() {
        AssessFraudResponse response = null;
        for (int attempt = 1; attempt <= 10; attempt++) {
            response = stub.assess(validRequestBuilder()
                    .setRequestId(deterministicRequestId("velocity-high-risk-" + attempt).toString())
                    .setCardToken("tok_velocityHighRisk01")
                    .build());
        }

        assertEquals(FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_HIGH_RISK, response.getAssessment());
        assertEquals(70, response.getRiskScore());
        assertEquals("TRANSACTION_COUNT", response.getMatchedRules(0).getRuleCode());
        assertEquals(
                "10 attempts in synthetic 60-second window met HIGH_RISK threshold 10",
                response.getMatchedRules(0).getEvidence());
    }

    @Test
    void conflictingRequestIdReturnsFailedPreconditionThroughProductionWiring() {
        stub.assess(validRequestBuilder().build());

        StatusRuntimeException exception = assertThrows(
                StatusRuntimeException.class,
                () -> stub.assess(validRequestBuilder().setAmount("11.00").build()));

        assertEquals(Status.Code.FAILED_PRECONDITION, exception.getStatus().getCode());
    }

    @Test
    void combinedExampleReturnsEveryMatchInDeterministicOrder() {
        AssessFraudResponse response = stub.assess(validRequestBuilder()
                .setAmount("2600.00")
                .setMerchantId("merchant-review")
                .setMerchantCategoryCode("7995")
                .build());

        assertEquals(FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_HIGH_RISK, response.getAssessment());
        assertEquals(95, response.getRiskScore());
        assertEquals(3, response.getMatchedRulesCount());

        assertEquals("AMOUNT_THRESHOLD", response.getMatchedRules(0).getRuleCode());
        assertEquals(
                RuleMatchSeverity.RULE_MATCH_SEVERITY_HIGH_RISK,
                response.getMatchedRules(0).getSeverity());
        assertEquals(
                "amount EUR 2600.00 met synthetic HIGH_RISK threshold 2500.00",
                response.getMatchedRules(0).getEvidence());

        assertEquals("MERCHANT_PROFILE", response.getMatchedRules(1).getRuleCode());
        assertEquals(
                RuleMatchSeverity.RULE_MATCH_SEVERITY_REVIEW,
                response.getMatchedRules(1).getSeverity());
        assertEquals(
                "merchant merchant-review has synthetic REVIEW profile",
                response.getMatchedRules(1).getEvidence());

        assertEquals("RISKY_MCC", response.getMatchedRules(2).getRuleCode());
        assertEquals(
                RuleMatchSeverity.RULE_MATCH_SEVERITY_REVIEW,
                response.getMatchedRules(2).getSeverity());
        assertEquals(
                "MCC 7995 has synthetic REVIEW classification",
                response.getMatchedRules(2).getEvidence());
        assertEquals(10, response.getMatchedRules(2).getScoreContribution());
    }

    @Test
    void aggregateAboveOneHundredIsCappedThroughRealGrpcTransport() {
        AssessFraudResponse response = stub.assess(validRequestBuilder()
                .setAmount("2600.00")
                .setMerchantId("merchant-high-risk")
                .build());

        assertEquals(FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_HIGH_RISK, response.getAssessment());
        assertTrue(response.hasRiskScore());
        assertEquals(100, response.getRiskScore());
        assertEquals(2, response.getMatchedRulesCount());
        assertEquals(70, response.getMatchedRules(0).getScoreContribution());
        assertEquals(75, response.getMatchedRules(1).getScoreContribution());
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidRequestReturnsInvalidArgumentThroughRealGrpcTransport(AssessFraudRequest request) {
        StatusRuntimeException exception =
                assertThrows(StatusRuntimeException.class, () -> stub.assess(request));

        assertEquals(Status.Code.INVALID_ARGUMENT, exception.getStatus().getCode());
    }

    static Stream<AssessFraudRequest> invalidRequests() {
        return Stream.of(
                validRequestBuilder().setRequestId("").build(),
                validRequestBuilder().setRequestId("not-a-uuid").build(),
                validRequestBuilder().setCardToken("").build(),
                validRequestBuilder()
                        .setChannel(TransactionChannel.TRANSACTION_CHANNEL_UNSPECIFIED)
                        .build(),
                validRequestBuilder().clearTransactionTime().build());
    }

    private static AssessFraudRequest.Builder validRequestBuilder() {
        return AssessFraudRequest.newBuilder()
                .setRequestId(REQUEST_ID.toString())
                .setCardToken("tok_A1B2C3D4")
                .setMerchantId("merchant-123")
                .setMerchantCategoryCode("5732")
                .setAmount("10.00")
                .setCurrency("EUR")
                .setCountry("DE")
                .setChannel(TransactionChannel.TRANSACTION_CHANNEL_ECOMMERCE)
                .setTransactionTime(Timestamp.newBuilder()
                        .setSeconds(Instant.parse("2026-07-19T10:15:30Z").getEpochSecond())
                        .build());
    }

    private static UUID deterministicRequestId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
