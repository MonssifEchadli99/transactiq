package io.github.monssifechadli99.transactiq.case_management.domain;

public final class FraudCaseResolutionPolicy {
    public Decision classify(
            FraudCase fraudCase,
            String analystId,
            long expectedVersion,
            FraudCaseResolutionOutcome outcome,
            String rationale) {
        if (fraudCase.status() == FraudCaseStatus.RESOLVED) {
            boolean sameResolution = analystId.equals(fraudCase.resolvedBy())
                    && outcome == fraudCase.resolutionOutcome()
                    && rationale.equals(fraudCase.resolutionRationale());
            if (!sameResolution) {
                return Decision.ALREADY_RESOLVED_DIFFERENTLY;
            }
            return expectedVersion != Long.MAX_VALUE
                            && fraudCase.version() == expectedVersion + 1
                    ? Decision.ALREADY_RESOLVED_IDENTICALLY
                    : Decision.VERSION_CONFLICT;
        }
        if (fraudCase.status() != FraudCaseStatus.IN_REVIEW) {
            return Decision.NOT_IN_REVIEW;
        }
        if (!analystId.equals(fraudCase.assigneeId())) {
            return Decision.NOT_ASSIGNED_TO_ANALYST;
        }
        if (fraudCase.version() != expectedVersion || expectedVersion == Long.MAX_VALUE) {
            return Decision.VERSION_CONFLICT;
        }
        return Decision.RESOLVABLE;
    }

    public enum Decision {
        RESOLVABLE,
        ALREADY_RESOLVED_IDENTICALLY,
        ALREADY_RESOLVED_DIFFERENTLY,
        NOT_IN_REVIEW,
        NOT_ASSIGNED_TO_ANALYST,
        VERSION_CONFLICT
    }
}
