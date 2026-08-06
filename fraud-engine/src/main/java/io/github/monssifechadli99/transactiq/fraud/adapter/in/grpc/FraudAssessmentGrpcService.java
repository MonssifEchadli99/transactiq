package io.github.monssifechadli99.transactiq.fraud.adapter.in.grpc;

import io.github.monssifechadli99.transactiq.fraud.application.port.in.FraudAssessmentUseCase;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.AssessFraudRequest;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.AssessFraudResponse;
import io.github.monssifechadli99.transactiq.fraud.contract.v1.FraudAssessmentServiceGrpc;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentRequest;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentResult;
import io.github.monssifechadli99.transactiq.fraud.domain.velocity.VelocityRequestConflictException;
import io.github.monssifechadli99.transactiq.fraud.domain.velocity.VelocityStoreUnavailableException;
import io.github.monssifechadli99.transactiq.observability.PortfolioMetrics;
import io.github.monssifechadli99.transactiq.observability.PortfolioMetrics.Signal;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class FraudAssessmentGrpcService
        extends FraudAssessmentServiceGrpc.FraudAssessmentServiceImplBase {

    private final FraudAssessmentUseCase fraudAssessmentUseCase;
    private final FraudAssessmentGrpcMapper mapper;
    private final PortfolioMetrics metrics;

    public FraudAssessmentGrpcService(
            FraudAssessmentUseCase fraudAssessmentUseCase,
            FraudAssessmentGrpcMapper mapper) {
        this(fraudAssessmentUseCase, mapper, PortfolioMetrics.noop());
    }

    @Autowired
    public FraudAssessmentGrpcService(
            FraudAssessmentUseCase fraudAssessmentUseCase,
            FraudAssessmentGrpcMapper mapper,
            PortfolioMetrics metrics) {
        this.fraudAssessmentUseCase =
                Objects.requireNonNull(fraudAssessmentUseCase, "fraudAssessmentUseCase must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    @Override
    public void assess(AssessFraudRequest request, StreamObserver<AssessFraudResponse> responseObserver) {
        try {
            FraudAssessmentRequest domainRequest = mapper.toDomainRequest(request);
            FraudAssessmentResult result = fraudAssessmentUseCase.assess(domainRequest);
            metrics.increment(switch (result.assessment()) {
                case CLEAR -> Signal.FRAUD_CLEAR;
                case REVIEW -> Signal.FRAUD_REVIEW;
                case HIGH_RISK -> Signal.FRAUD_HIGH_RISK;
            });
            responseObserver.onNext(mapper.toContractResponse(result));
            responseObserver.onCompleted();
        } catch (FraudAssessmentRequestRejectedException e) {
            metrics.increment(Signal.FRAUD_INVALID);
            responseObserver.onError(
                    Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (VelocityRequestConflictException e) {
            metrics.increment(Signal.FRAUD_CONFLICT);
            responseObserver.onError(
                    Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
        } catch (VelocityStoreUnavailableException e) {
            metrics.increment(Signal.FRAUD_UNAVAILABLE);
            responseObserver.onError(
                    Status.UNAVAILABLE.withDescription(e.getMessage()).asRuntimeException());
        }
    }
}
