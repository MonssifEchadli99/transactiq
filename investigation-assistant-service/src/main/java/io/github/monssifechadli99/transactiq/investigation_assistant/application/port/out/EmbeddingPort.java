package io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out;

/**
 * Strategy port for producing an embedding vector from safe evidence text.
 * There is exactly one production implementation (OpenAI via Spring AI); tests
 * substitute a deterministic local double. No provider selection or fallback.
 */
public interface EmbeddingPort {
    float[] embed(String text);
}
