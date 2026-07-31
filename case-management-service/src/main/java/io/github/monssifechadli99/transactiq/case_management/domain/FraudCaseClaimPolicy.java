package io.github.monssifechadli99.transactiq.case_management.domain;

public final class FraudCaseClaimPolicy {
    public Decision classify(FraudCase fraudCase, String analystId, long expectedVersion) {
        if (fraudCase.status() == FraudCaseStatus.IN_REVIEW) {
            return analystId.equals(fraudCase.assigneeId())
                    ? Decision.ALREADY_CLAIMED_BY_ANALYST
                    : Decision.ALREADY_ASSIGNED;
        }
        if (fraudCase.status() != FraudCaseStatus.NEW) {
            return Decision.NOT_CLAIMABLE;
        }
        if (fraudCase.assigneeId() != null) {
            return Decision.ALREADY_ASSIGNED;
        }
        if (fraudCase.version() != expectedVersion) {
            return Decision.VERSION_CONFLICT;
        }
        return Decision.CLAIMABLE;
    }

    public enum Decision {
        CLAIMABLE,
        ALREADY_CLAIMED_BY_ANALYST,
        ALREADY_ASSIGNED,
        VERSION_CONFLICT,
        NOT_CLAIMABLE
    }
}
