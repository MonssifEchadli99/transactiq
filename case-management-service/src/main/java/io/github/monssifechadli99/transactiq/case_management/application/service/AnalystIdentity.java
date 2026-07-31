package io.github.monssifechadli99.transactiq.case_management.application.service;

public final class AnalystIdentity {
    private AnalystIdentity() {}

    public static String required(String value) {
        if (value == null) {
            throw new InvalidFraudCaseRequestException("INVALID_ANALYST_ID");
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 128 || containsControlCharacter(trimmed)) {
            throw new InvalidFraudCaseRequestException("INVALID_ANALYST_ID");
        }
        return trimmed;
    }

    private static boolean containsControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }
}
