package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.out.embedding.openai;

import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EmbeddingPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EmbeddingProviderUnavailableException;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Production {@link EmbeddingPort} strategy backed by Spring AI's OpenAI embedding
 * client. This is the only production AI provider; there is no factory, enum, or
 * fallback by design. Never logs the request text or the returned vector.
 */
public final class OpenAiEmbeddingAdapter implements EmbeddingPort {

    private final EmbeddingModel embeddingModel;

    public OpenAiEmbeddingAdapter(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public float[] embed(String text) {
        try {
            return embeddingModel.embed(text);
        } catch (RuntimeException error) {
            throw new EmbeddingProviderUnavailableException("OpenAI embedding request failed");
        }
    }
}
