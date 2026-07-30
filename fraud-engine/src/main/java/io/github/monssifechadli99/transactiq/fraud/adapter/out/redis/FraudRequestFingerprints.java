package io.github.monssifechadli99.transactiq.fraud.adapter.out.redis;

import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class FraudRequestFingerprints {

    private FraudRequestFingerprints() {}

    static String tokenFingerprint(String cardToken) {
        return sha256(cardToken);
    }

    static String requestFingerprint(FraudAssessmentRequest request) {
        MessageDigest digest = sha256Digest();
        addField(digest, request.requestId().toString());
        addField(digest, request.cardToken());
        addField(digest, request.merchantId());
        addField(digest, request.merchantCategoryCode());
        addField(digest, request.amount().stripTrailingZeros().toPlainString());
        addField(digest, request.currency());
        addField(digest, request.country());
        addField(digest, request.channel().name());
        addField(digest, Long.toString(request.transactionTime().getEpochSecond()));
        addField(digest, Integer.toString(request.transactionTime().getNano()));
        return toHex(digest.digest());
    }

    static String keyComponentFingerprint(String value) {
        return sha256(value);
    }

    private static String sha256(String value) {
        MessageDigest digest = sha256Digest();
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        return toHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void addField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(Character.forDigit((value >>> 4) & 0xf, 16));
            hex.append(Character.forDigit(value & 0xf, 16));
        }
        return hex.toString();
    }
}
