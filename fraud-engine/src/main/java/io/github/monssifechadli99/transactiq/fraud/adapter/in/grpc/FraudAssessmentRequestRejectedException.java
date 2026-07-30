package io.github.monssifechadli99.transactiq.fraud.adapter.in.grpc;

final class FraudAssessmentRequestRejectedException extends RuntimeException {

    FraudAssessmentRequestRejectedException(String message) {
        super(message);
    }
}
