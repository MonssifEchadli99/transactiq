package io.github.monssifechadli99.transactiq.investigation_assistant.support;

import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EmbeddingPort;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic, offline stand-in for the OpenAI embedding strategy. Buckets a light
 * stem (trailing "s" stripped) of each token into a fixed-size hashed vector and L2
 * normalizes it, so texts sharing vocabulary land close in cosine space without ever
 * calling a real provider. Used only from tests.
 */
public final class DeterministicEmbeddingAdapter implements EmbeddingPort {

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9]+");

    private final int dimensions;

    public DeterministicEmbeddingAdapter(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dimensions];
        for (String token : TOKEN_SPLIT.split(text.toLowerCase(Locale.ROOT))) {
            String stem = token.endsWith("s") && token.length() > 3
                    ? token.substring(0, token.length() - 1)
                    : token;
            if (stem.isBlank()) {
                continue;
            }
            int bucket = Math.floorMod(stem.hashCode(), dimensions);
            vector[bucket] += 1.0f;
        }
        return normalize(vector);
    }

    private static float[] normalize(float[] vector) {
        double sumSquares = 0;
        for (float value : vector) {
            sumSquares += (double) value * value;
        }
        if (sumSquares == 0) {
            vector[0] = 1.0f;
            return vector;
        }
        float norm = (float) Math.sqrt(sumSquares);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
        return vector;
    }
}
