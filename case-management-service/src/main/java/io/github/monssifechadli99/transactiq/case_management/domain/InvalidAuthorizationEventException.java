package io.github.monssifechadli99.transactiq.case_management.domain;

public final class InvalidAuthorizationEventException extends RuntimeException {

    public InvalidAuthorizationEventException(String message) {
        super(message);
    }

    public InvalidAuthorizationEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
