package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.out.demo;

import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EmbeddingPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/** Deterministic, local-only embedding fixture for the explicit demo profile. */
public final class DemoOfflineEmbeddingAdapter implements EmbeddingPort {

    private final int dimensions;

    public DemoOfflineEmbeddingAdapter(int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        this.dimensions = dimensions;
    }

    @Override
    public float[] embed(String text) {
        Objects.requireNonNull(text, "text must not be null");
        byte[] digest = sha256(text);
        float[] embedding = new float[dimensions];
        double squaredMagnitude = 0.0;
        for (int index = 0; index < dimensions; index++) {
            int unsigned = Byte.toUnsignedInt(digest[index % digest.length]);
            float value = (unsigned - 127.5f) / 127.5f;
            embedding[index] = value;
            squaredMagnitude += value * value;
        }
        float magnitude = (float) Math.sqrt(squaredMagnitude);
        if (magnitude == 0.0f) {
            embedding[0] = 1.0f;
            return embedding;
        }
        for (int index = 0; index < embedding.length; index++) {
            embedding[index] /= magnitude;
        }
        return embedding;
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
