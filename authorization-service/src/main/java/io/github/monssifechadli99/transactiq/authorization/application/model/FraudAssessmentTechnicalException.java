package io.github.monssifechadli99.transactiq.authorization.application.model;

public final class FraudAssessmentTechnicalException extends RuntimeException {

    public FraudAssessmentTechnicalException(String message) {
        super(message);
    }

    public FraudAssessmentTechnicalException(String message, Throwable cause) {
        super(message, cause);
    }
}
