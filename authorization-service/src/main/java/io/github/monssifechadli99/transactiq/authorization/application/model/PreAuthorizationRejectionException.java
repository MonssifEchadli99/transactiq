package io.github.monssifechadli99.transactiq.authorization.application.model;

import java.util.Objects;

public final class PreAuthorizationRejectionException extends RuntimeException {

    private final Reason reason;

    public PreAuthorizationRejectionException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason must not be null").name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        UNKNOWN_CARD_TOKEN,
        UNSUPPORTED_CURRENCY
    }
}
