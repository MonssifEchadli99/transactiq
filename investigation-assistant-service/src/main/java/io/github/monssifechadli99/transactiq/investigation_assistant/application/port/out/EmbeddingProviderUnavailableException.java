package io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out;

public final class EmbeddingProviderUnavailableException extends RuntimeException {
    public EmbeddingProviderUnavailableException(String message) {
        super(message);
    }
}
