package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.out.embedding.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EmbeddingProviderUnavailableException;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

class OpenAiEmbeddingAdapterTest {

    @Test
    void providerFailureCannotExposeRequestOrProviderResponseThroughThePublicException() {
        String requestSentinel = "EMBEDDING_REQUEST_LOG_SENTINEL_6A";
        String providerSentinel = "PROVIDER_ERROR_BODY_LOG_SENTINEL_6A";
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(requestSentinel)).thenThrow(new IllegalStateException(
                providerSentinel + " at https://provider.invalid/v1/embeddings?credential=secret-sentinel"));

        EmbeddingProviderUnavailableException failure = assertThrows(
                EmbeddingProviderUnavailableException.class,
                () -> new OpenAiEmbeddingAdapter(model).embed(requestSentinel));

        assertEquals("OpenAI embedding request failed", failure.getMessage());
        assertNull(failure.getCause(), "raw provider failures must not survive as public exception causes");
        String stackTrace = stackTrace(failure);
        assertFalse(stackTrace.contains(requestSentinel), stackTrace);
        assertFalse(stackTrace.contains(providerSentinel), stackTrace);
        assertFalse(stackTrace.contains("secret-sentinel"), stackTrace);
    }

    private static String stackTrace(Throwable error) {
        StringWriter text = new StringWriter();
        error.printStackTrace(new PrintWriter(text));
        return text.toString();
    }
}
