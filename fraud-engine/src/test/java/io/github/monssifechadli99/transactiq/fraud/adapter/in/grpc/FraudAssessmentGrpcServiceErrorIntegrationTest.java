package io.github.monssifechadli99.transactiq.fraud.adapter.in.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.Timestamp;
import io.github.monssifechadli99.transactiq.fraud.application.port.in.FraudAssessmentUseCase;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.AssessFraudRequest;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.FraudAssessmentServiceGrpc;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.TransactionChannel;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentRequest;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentResult;
import io.github.monssifechadli99.transactiq.fraud.domain.velocity.VelocityRequestConflictException;
import io.github.monssifechadli99.transactiq.fraud.domain.velocity.VelocityStoreUnavailableException;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FraudAssessmentGrpcServiceErrorIntegrationTest {

    private static final String CARD_TOKEN = "tok_A1B2C3D4";
    private static final String INVALID_CARD_TOKEN = "tok_sensitive!";

    private Server server;
    private ManagedChannel channel;
    private ThrowingFraudAssessmentUseCase useCase;
    private FraudAssessmentServiceGrpc.FraudAssessmentServiceBlockingStub stub;

    @BeforeEach
    void startInProcessServer() throws IOException {
        useCase = new ThrowingFraudAssessmentUseCase();
        String serverName = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new FraudAssessmentGrpcService(useCase, new FraudAssessmentGrpcMapper()))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        stub = FraudAssessmentServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void stopInProcessServer() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void velocityStoreFailureMapsToUnavailableWithoutSensitiveDetails() {
        useCase.failure = new VelocityStoreUnavailableException(new IllegalStateException("internal"));

        StatusRuntimeException exception =
                assertThrows(StatusRuntimeException.class, () -> stub.assess(validRequest()));

        assertEquals(Status.Code.UNAVAILABLE, exception.getStatus().getCode());
        assertFalse(exception.getStatus().getDescription().contains(CARD_TOKEN));
    }

    @Test
    void conflictingRequestIdMapsToFailedPrecondition() {
        useCase.failure = new VelocityRequestConflictException();

        StatusRuntimeException exception =
                assertThrows(StatusRuntimeException.class, () -> stub.assess(validRequest()));

        assertEquals(Status.Code.FAILED_PRECONDITION, exception.getStatus().getCode());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void invalidRequestMapsToInvalidArgumentBeforeUseCaseOrRedis(
            String scenario, AssessFraudRequest request) {
        StatusRuntimeException exception =
                assertThrows(StatusRuntimeException.class, () -> stub.assess(request));

        assertEquals(Status.Code.INVALID_ARGUMENT, exception.getStatus().getCode());
        assertEquals(0, useCase.invocationCount);
        assertFalse(exception.getStatus().getDescription().contains(CARD_TOKEN));
        assertFalse(exception.getStatus().getDescription().contains(INVALID_CARD_TOKEN));
    }

    private static AssessFraudRequest validRequest() {
        return validRequestBuilder().build();
    }

    private static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of(
                        "invalid card token",
                        validRequestBuilder().setCardToken(INVALID_CARD_TOKEN).build()),
                Arguments.of(
                        "merchant ID over 64 characters",
                        validRequestBuilder().setMerchantId("m".repeat(65)).build()),
                Arguments.of(
                        "non-four-digit MCC",
                        validRequestBuilder().setMerchantCategoryCode("54A1").build()),
                Arguments.of(
                        "nonpositive amount",
                        validRequestBuilder().setAmount("0").build()),
                Arguments.of(
                        "amount over 12 integer digits",
                        validRequestBuilder().setAmount("1000000000000.00").build()),
                Arguments.of(
                        "amount over 2 fraction digits",
                        validRequestBuilder().setAmount("1.001").build()),
                Arguments.of(
                        "lowercase currency",
                        validRequestBuilder().setCurrency("eur").build()),
                Arguments.of(
                        "invalid country length",
                        validRequestBuilder().setCountry("DEU").build()),
                Arguments.of(
                        "invalid protobuf timestamp",
                        validRequestBuilder()
                                .setTransactionTime(Timestamp.newBuilder()
                                        .setNanos(1_000_000_000)
                                        .build())
                                .build()));
    }

    private static AssessFraudRequest.Builder validRequestBuilder() {
        return AssessFraudRequest.newBuilder()
                .setRequestId(UUID.fromString("f2b1c9d0-6e3a-4c1b-9b7a-2b6a1e9c7d44").toString())
                .setCardToken(CARD_TOKEN)
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

    private static final class ThrowingFraudAssessmentUseCase implements FraudAssessmentUseCase {

        private RuntimeException failure;
        private int invocationCount;

        @Override
        public FraudAssessmentResult assess(FraudAssessmentRequest request) {
            invocationCount++;
            throw failure;
        }
    }
}
