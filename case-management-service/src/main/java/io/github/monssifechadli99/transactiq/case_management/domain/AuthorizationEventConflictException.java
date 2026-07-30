package io.github.monssifechadli99.transactiq.case_management.domain;

public final class AuthorizationEventConflictException extends RuntimeException {

    public AuthorizationEventConflictException(String message) {
        super(message);
    }
}
