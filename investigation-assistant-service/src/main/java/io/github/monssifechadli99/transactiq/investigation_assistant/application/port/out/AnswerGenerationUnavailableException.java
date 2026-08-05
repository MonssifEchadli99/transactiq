package io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out;

/** Cause-free public failure used for provider and malformed-output errors. */
public final class AnswerGenerationUnavailableException extends RuntimeException {

    public static final String SAFE_MESSAGE = "Investigation answer generation is unavailable";

    public AnswerGenerationUnavailableException() {
        super(SAFE_MESSAGE);
    }

    /**
     * Keeps deterministic fakes convenient while ensuring caller-controlled text can never
     * become a public exception message.
     */
    public AnswerGenerationUnavailableException(String ignored) {
        super(SAFE_MESSAGE);
    }
}
