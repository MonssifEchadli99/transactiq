package io.github.monssifechadli99.transactiq.fraud.domain.velocity;

public final class VelocityStoreUnavailableException extends RuntimeException {

    public VelocityStoreUnavailableException(Throwable cause) {
        super("velocity store is unavailable", cause);
    }
}
