package io.github.monssifechadli99.transactiq.authorization.adapter.out.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationChannel;
import io.github.monssifechadli99.transactiq.authorization.application.model.FraudAssessmentConflictException;
import io.github.monssifechadli99.transactiq.authorization.application.model.FraudAssessmentTechnicalException;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessment;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessmentResult;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.AssessFraudRequest;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.AssessFraudResponse;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.FraudAssessmentOutcome;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.FraudAssessmentServiceGrpc;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.RuleMatch;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.RuleMatchSeverity;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FraudGrpcClientAdapterTcpIntegrationTest {

    private final FakeFraudService service = new FakeFraudService();

    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void startTcpServer() throws Exception {
        server = ServerBuilder.forPort(0).addService(service).build().start();
        channel = ManagedChannelBuilder.forAddress("localhost", server.getPort())
                .usePlaintext()
                .disableRetry()
                .build();
    }

    @AfterEach
    void stopTcpServer() throws Exception {
        channel.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow();
        server.awaitTermination(5, TimeUnit.SECONDS);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validResponses")
    void callsRealTcpServerAndMapsAllValidResponses(
            String description,
            AssessFraudResponse response,
            FraudAssessment expectedAssessment,
            int expectedMatchCount) {
        service.respondWith(response);
        FraudGrpcClientAdapter client = client(Duration.ofSeconds(1));

        FraudAssessmentResult result = client.assess(command());

        assertEquals(expectedAssessment, result.assessment());
        assertEquals(response.getRiskScore(), result.riskScore());
        assertEquals(expectedMatchCount, result.matchedRules().size());
        assertEquals(1, service.invocationCount());
        AssessFraudRequest received = service.lastRequest();
        assertEquals(command().requestId().toString(), received.getRequestId());
        assertEquals("42.5000", received.getAmount());
        assertEquals(command().transactionTime().getEpochSecond(),
                received.getTransactionTime().getSeconds());
        assertEquals(command().transactionTime().getNano(),
                received.getTransactionTime().getNanos());
    }

    @Test
    void mapsFailedPreconditionToFrameworkIndependentConflict() {
        service.failWith(Status.FAILED_PRECONDITION);

        assertThrows(
                FraudAssessmentConflictException.class,
                () -> client(Duration.ofSeconds(1)).assess(command()));
        assertEquals(1, service.invocationCount());
    }

    @ParameterizedTest
    @MethodSource("technicalStatuses")
    void mapsGrpcTechnicalFailuresWithoutClientRetry(Status status) {
        service.failWith(status);

        assertThrows(
                FraudAssessmentTechnicalException.class,
                () -> client(Duration.ofSeconds(1)).assess(command()));
        assertEquals(1, service.invocationCount());
    }

    @Test
    void enforcesDeadlineWithoutClientRetry() {
        service.neverRespond();

        assertThrows(
                FraudAssessmentTechnicalException.class,
                () -> client(Duration.ofMillis(50)).assess(command()));
        assertEquals(1, service.invocationCount());
    }

    @Test
    void treatsMalformedTcpResponseAsTechnicalFailure() {
        service.respondWith(AssessFraudResponse.getDefaultInstance());

        assertThrows(
                FraudAssessmentTechnicalException.class,
                () -> client(Duration.ofSeconds(1)).assess(command()));
        assertEquals(1, service.invocationCount());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedScoringResponses")
    void rejectsEveryMalformedScoringResponseOverTcp(
            String description, AssessFraudResponse response) {
        service.respondWith(response);

        assertThrows(
                FraudAssessmentTechnicalException.class,
                () -> client(Duration.ofSeconds(1)).assess(command()));
        assertEquals(1, service.invocationCount());
    }

    private FraudGrpcClientAdapter client(Duration deadline) {
        return new FraudGrpcClientAdapter(channel, deadline, new FraudGrpcMapper());
    }

    private static Stream<Arguments> validResponses() {
        RuleMatch review = rule(
                "AMOUNT_REVIEW", RuleMatchSeverity.RULE_MATCH_SEVERITY_REVIEW, 15);
        RuleMatch high = rule(
                "MCC_HIGH", RuleMatchSeverity.RULE_MATCH_SEVERITY_HIGH_RISK, 70);
        return Stream.of(
                Arguments.of(
                        "clear",
                        response(FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_CLEAR, 0),
                        FraudAssessment.CLEAR,
                        0),
                Arguments.of(
                        "review",
                        response(FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_REVIEW, 15, review),
                        FraudAssessment.REVIEW,
                        1),
                Arguments.of(
                        "high risk with multiple matches",
                        response(
                                FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_HIGH_RISK,
                                85,
                                review,
                                high),
                        FraudAssessment.HIGH_RISK,
                        2));
    }

    private static Stream<Arguments> malformedScoringResponses() {
        RuleMatch review = rule(
                "A_REVIEW", RuleMatchSeverity.RULE_MATCH_SEVERITY_REVIEW, 15);
        RuleMatch high = rule(
                "B_HIGH", RuleMatchSeverity.RULE_MATCH_SEVERITY_HIGH_RISK, 70);
        RuleMatch contributionAbsent = RuleMatch.newBuilder()
                .setRuleCode("A_REVIEW")
                .setSeverity(RuleMatchSeverity.RULE_MATCH_SEVERITY_REVIEW)
                .setEvidence("Synthetic evidence")
                .build();
        return Stream.of(
                Arguments.of("risk score absent", AssessFraudResponse.newBuilder()
                        .setAssessment(FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_CLEAR)
                        .build()),
                Arguments.of("contribution absent", response(
                        FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_REVIEW,
                        15,
                        contributionAbsent)),
                Arguments.of("CLEAR not zero", response(
                        FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_CLEAR, 1)),
                Arguments.of("REVIEW outside band", response(
                        FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_REVIEW,
                        70,
                        rule("A_REVIEW", RuleMatchSeverity.RULE_MATCH_SEVERITY_REVIEW, 70))),
                Arguments.of("HIGH_RISK outside band", response(
                        FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_HIGH_RISK,
                        69,
                        rule("B_HIGH", RuleMatchSeverity.RULE_MATCH_SEVERITY_HIGH_RISK, 69))),
                Arguments.of("contribution below range", response(
                        FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_REVIEW,
                        0,
                        rule("A_REVIEW", RuleMatchSeverity.RULE_MATCH_SEVERITY_REVIEW, 0))),
                Arguments.of("contribution above range", response(
                        FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_HIGH_RISK,
                        100,
                        rule("B_HIGH", RuleMatchSeverity.RULE_MATCH_SEVERITY_HIGH_RISK, 101))),
                Arguments.of("total differs from capped sum", response(
                        FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_HIGH_RISK,
                        99,
                        rule("A_HIGH", RuleMatchSeverity.RULE_MATCH_SEVERITY_HIGH_RISK, 70),
                        rule("B_HIGH", RuleMatchSeverity.RULE_MATCH_SEVERITY_HIGH_RISK, 75))),
                Arguments.of("assessment disagrees with score and matches", response(
                        FraudAssessmentOutcome.FRAUD_ASSESSMENT_OUTCOME_REVIEW, 85, review, high)));
    }

    private static Stream<Status> technicalStatuses() {
        return Stream.of(Status.UNAVAILABLE, Status.INVALID_ARGUMENT, Status.INTERNAL);
    }

    private static AssessFraudResponse response(
            FraudAssessmentOutcome assessment, int riskScore, RuleMatch... matches) {
        return AssessFraudResponse.newBuilder()
                .setAssessment(assessment)
                .setRiskScore(riskScore)
                .addAllMatchedRules(List.of(matches))
                .build();
    }

    private static RuleMatch rule(
            String code, RuleMatchSeverity severity, int scoreContribution) {
        return RuleMatch.newBuilder()
                .setRuleCode(code)
                .setSeverity(severity)
                .setEvidence("Synthetic evidence for " + code)
                .setScoreContribution(scoreContribution)
                .build();
    }

    private static AuthorizationCommand command() {
        return new AuthorizationCommand(
                UUID.fromString("bbaf80f4-fdf4-4997-bacb-93a9e82bdc58"),
                "tok_A1B2C3D4",
                "merchant-123",
                "5411",
                new BigDecimal("42.5000"),
                "EUR",
                "DE",
                AuthorizationChannel.ECOMMERCE,
                Instant.parse("2026-07-21T10:15:30.123456789Z"));
    }

    private static final class FakeFraudService
            extends FraudAssessmentServiceGrpc.FraudAssessmentServiceImplBase {

        private final AtomicInteger invocationCount = new AtomicInteger();
        private final AtomicReference<AssessFraudRequest> lastRequest = new AtomicReference<>();
        private volatile Behavior behavior = (request, observer) -> observer.onError(
                Status.INTERNAL.asRuntimeException());

        @Override
        public void assess(
                AssessFraudRequest request,
                StreamObserver<AssessFraudResponse> responseObserver) {
            invocationCount.incrementAndGet();
            lastRequest.set(request);
            behavior.respond(request, responseObserver);
        }

        private void respondWith(AssessFraudResponse response) {
            behavior = (request, observer) -> {
                observer.onNext(response);
                observer.onCompleted();
            };
        }

        private void failWith(Status status) {
            behavior = (request, observer) -> observer.onError(status.asRuntimeException());
        }

        private void neverRespond() {
            behavior = (request, observer) -> {};
        }

        private int invocationCount() {
            return invocationCount.get();
        }

        private AssessFraudRequest lastRequest() {
            return lastRequest.get();
        }
    }

    @FunctionalInterface
    private interface Behavior {
        void respond(
                AssessFraudRequest request,
                StreamObserver<AssessFraudResponse> responseObserver);
    }
}
