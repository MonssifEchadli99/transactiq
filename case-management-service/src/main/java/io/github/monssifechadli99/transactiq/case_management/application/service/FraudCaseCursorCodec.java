package io.github.monssifechadli99.transactiq.case_management.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

public final class FraudCaseCursorCodec {

    public String encode(Instant createdAt, UUID caseId) {
        String value = createdAt + "|" + caseId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public Cursor decode(String encoded) {
        try {
            String value = new String(
                    Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = value.indexOf('|');
            if (separator <= 0 || separator != value.lastIndexOf('|')) {
                throw new IllegalArgumentException("invalid cursor");
            }
            return new Cursor(
                    Instant.parse(value.substring(0, separator)),
                    UUID.fromString(value.substring(separator + 1)));
        } catch (IllegalArgumentException invalid) {
            throw new InvalidFraudCaseRequestException("INVALID_CURSOR");
        }
    }

    public record Cursor(Instant createdAt, UUID caseId) {}
}
