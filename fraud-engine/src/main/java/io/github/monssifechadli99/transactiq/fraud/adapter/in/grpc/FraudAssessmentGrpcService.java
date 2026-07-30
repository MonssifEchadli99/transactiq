package io.github.monssifechadli99.transactiq.fraud.adapter.in.grpc;

import io.github.monssifechadli99.transactiq.fraud.application.port.in.FraudAssessmentUseCase;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.AssessFraudRequest;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.AssessFraudResponse;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.FraudAssessmentServiceGrpc;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentRequest;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentResult;
import io.github.monssifechadli99.transactiq.fraud.domain.velocity.VelocityRequestConflictException;
import io.github.monssifechadli99.transactiq.fraud.domain.velocity.VelocityStoreUnavailableException;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class FraudAssessmentGrpcService
        extends FraudAssessmentServiceGrpc.FraudAssessmentServiceImplBase {

    private final FraudAssessmentUseCase fraudAssessmentUseCase;
    private final FraudAssessmentGrpcMapper mapper;

    public FraudAssessmentGrpcService(
            FraudAssessmentUseCase fraudAssessmentUseCase,
            FraudAssessmentGrpcMapper mapper) {
        this.fraudAssessmentUseCase =
                Objects.requireNonNull(fraudAssessmentUseCase, "fraudAssessmentUseCase must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public void assess(AssessFraudRequest request, StreamObserver<AssessFraudResponse> responseObserver) {
        try {
            FraudAssessmentRequest domainRequest = mapper.toDomainRequest(request);
            FraudAssessmentResult result = fraudAssessmentUseCase.assess(domainRequest);
            responseObserver.onNext(mapper.toContractResponse(result));
            responseObserver.onCompleted();
        } catch (FraudAssessmentRequestRejectedException e) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (VelocityRequestConflictException e) {
            responseObserver.onError(
                    Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
        } catch (VelocityStoreUnavailableException e) {
            responseObserver.onError(
                    Status.UNAVAILABLE.withDescription(e.getMessage()).asRuntimeException());
        }
    }
}
