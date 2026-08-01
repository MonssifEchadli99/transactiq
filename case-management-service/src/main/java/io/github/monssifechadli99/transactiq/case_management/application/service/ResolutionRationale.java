package io.github.monssifechadli99.transactiq.case_management.application.service;

public final class ResolutionRationale {
    private ResolutionRationale() {}

    public static String normalize(String value) {
        if (value == null) {
            throw new InvalidFraudCaseRequestException("INVALID_RESOLUTION_RATIONALE");
        }
        String normalized = value.trim();
        if (normalized.length() < 10 || normalized.length() > 2_000
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new InvalidFraudCaseRequestException("INVALID_RESOLUTION_RATIONALE");
        }
        return normalized;
    }
}
