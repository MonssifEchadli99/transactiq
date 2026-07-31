package io.github.monssifechadli99.transactiq.case_management.application.service;

public final class FraudCaseConflictException extends RuntimeException {
    private final String code;

    public FraudCaseConflictException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
