package io.github.monssifechadli99.transactiq.authorization.application.model;

public final class FraudAssessmentConflictException extends RuntimeException {

    public FraudAssessmentConflictException() {
        super("Fraud assessment request identifier conflicts with a prior payload");
    }
}
