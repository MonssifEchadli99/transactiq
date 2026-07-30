package io.github.monssifechadli99.transactiq.authorization.adapter.out.grpc;

import io.github.monssifechadli99.transactiq.authorization.application.model.FraudAssessmentConflictException;
import io.github.monssifechadli99.transactiq.authorization.application.model.FraudAssessmentTechnicalException;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.FraudAssessmentPort;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessmentResult;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.AssessFraudResponse;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.FraudAssessmentServiceGrpc;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class FraudGrpcClientAdapter implements FraudAssessmentPort {

    private final FraudAssessmentServiceGrpc.FraudAssessmentServiceBlockingStub stub;
    private final Duration deadline;
    private final FraudGrpcMapper mapper;

    public FraudGrpcClientAdapter(
            Channel channel, Duration deadline, FraudGrpcMapper mapper) {
        this.stub = FraudAssessmentServiceGrpc.newBlockingStub(
                Objects.requireNonNull(channel, "channel must not be null"));
        this.deadline = requirePositive(deadline);
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public FraudAssessmentResult assess(AuthorizationCommand command) {
        try {
            AssessFraudResponse response = stub
                    .withDeadlineAfter(deadline.toNanos(), TimeUnit.NANOSECONDS)
                    .assess(mapper.toRequest(command));
            return mapper.toResult(response);
        } catch (FraudAssessmentTechnicalException malformedResponse) {
            throw malformedResponse;
        } catch (StatusRuntimeException grpcFailure) {
            if (grpcFailure.getStatus().getCode() == Status.Code.FAILED_PRECONDITION) {
                throw new FraudAssessmentConflictException();
            }
            String message = grpcFailure.getStatus().getCode() == Status.Code.INVALID_ARGUMENT
                    ? "Fraud assessment rejected a validated request"
                    : "Fraud assessment call failed";
            throw new FraudAssessmentTechnicalException(message, grpcFailure);
        } catch (RuntimeException unexpectedFailure) {
            throw new FraudAssessmentTechnicalException(
                    "Fraud assessment call failed", unexpectedFailure);
        }
    }

    private static Duration requirePositive(Duration deadline) {
        Objects.requireNonNull(deadline, "deadline must not be null");
        if (deadline.isZero() || deadline.isNegative()) {
            throw new IllegalArgumentException("deadline must be positive");
        }
        return deadline;
    }
}
