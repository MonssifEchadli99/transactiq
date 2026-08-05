package io.github.monssifechadli99.transactiq.investigation_assistant.domain;

import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudCaseProjectionSnapshot;
import java.util.Objects;

/**
 * A projection that passed contract validation together with its private integrity
 * discriminator. The discriminator is deliberately omitted from {@link #toString()}.
 */
public final class ValidatedProjection {

    private final FraudCaseProjectionSnapshot snapshot;
    private final String integrityDiscriminator;

    public ValidatedProjection(
            FraudCaseProjectionSnapshot snapshot, String integrityDiscriminator) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.integrityDiscriminator = Objects.requireNonNull(
                integrityDiscriminator, "integrityDiscriminator");
    }

    public FraudCaseProjectionSnapshot snapshot() {
        return snapshot;
    }

    public String integrityDiscriminator() {
        return integrityDiscriminator;
    }

    @Override
    public String toString() {
        return "ValidatedProjection[caseId=" + snapshot.getCaseId()
                + ", status=" + snapshot.getStatus()
                + ", aggregateVersion=" + snapshot.getAggregateVersion() + "]";
    }
}
