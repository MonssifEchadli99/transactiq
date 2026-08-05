package io.github.monssifechadli99.transactiq.investigation_assistant.support;

import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EmbeddingPort;
import java.util.ArrayList;
import java.util.List;

/** In-memory {@link EmbeddingPort} fake that records every call, for unit tests. */
public final class FakeEmbeddingPort implements EmbeddingPort {

    private final int dimensions;
    private final List<String> requestedTexts = new ArrayList<>();

    public FakeEmbeddingPort(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public float[] embed(String text) {
        requestedTexts.add(text);
        return new float[dimensions];
    }

    public int callCount() {
        return requestedTexts.size();
    }

    public List<String> requestedTexts() {
        return List.copyOf(requestedTexts);
    }
}
