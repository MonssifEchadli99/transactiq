package io.github.monssifechadli99.transactiq.fraud.domain.velocity;

public final class VelocityRequestConflictException extends RuntimeException {

    public VelocityRequestConflictException() {
        super("requestId was reused with different fraud-relevant request data");
    }
}
