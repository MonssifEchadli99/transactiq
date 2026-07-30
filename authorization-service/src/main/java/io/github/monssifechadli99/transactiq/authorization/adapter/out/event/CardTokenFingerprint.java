package io.github.monssifechadli99.transactiq.authorization.adapter.out.event;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class CardTokenFingerprint {

    private CardTokenFingerprint() {}

    public static String sha256(String cardToken) {
        if (cardToken == null || cardToken.isBlank()) {
            throw new IllegalArgumentException("cardToken must not be blank");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(cardToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailableAlgorithm) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailableAlgorithm);
        }
    }
}
