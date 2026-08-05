package io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out;

public final class EvidenceStoreUnavailableException extends RuntimeException {
    public EvidenceStoreUnavailableException(String message) {
        super(message);
    }

    public EvidenceStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
