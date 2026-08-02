package io.github.monssifechadli99.transactiq.case_management.projection;

import io.github.monssifechadli99.transactiq.case_management.domain.FraudCase;
import io.github.monssifechadli99.transactiq.case_management.domain.AuthorizationEventSnapshot;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class FraudCaseProjectionOutbox {
    private final JdbcClient jdbc;
    private final FraudCaseProjectionMapper mapper;
    private final Supplier<UUID> eventIds;

    public FraudCaseProjectionOutbox(JdbcClient jdbc, FraudCaseProjectionMapper mapper, Supplier<UUID> eventIds) {
        this.jdbc = jdbc; this.mapper = mapper; this.eventIds = eventIds;
    }

    public boolean append(FraudCase fraudCase, FraudCaseProjectionType type) {
        var event = mapper.map(fraudCase, type, eventIds.get());
        int inserted = jdbc.sql("""
                INSERT INTO fraud_case.fraud_case_projection_outbox
                    (event_id, fraud_case_id, aggregate_version, event_type, snapshot_hash, payload,
                     occurred_at, created_at, publication_state, attempt_count, next_attempt_at)
                VALUES (:eventId, :caseId, :version, :type, :hash, :payload,
                        :occurredAt, :createdAt, 'PENDING', 0, :createdAt)
                ON CONFLICT (fraud_case_id, aggregate_version) DO NOTHING
                """)
                .param("eventId", UUID.fromString(event.getEventId()))
                .param("caseId", fraudCase.caseId()).param("version", fraudCase.version())
                .param("type", type.name()).param("hash", event.getSnapshotHash())
                .param("payload", event.toByteArray())
                .param("occurredAt", OffsetDateTime.ofInstant(fraudCase.updatedAt(), ZoneOffset.UTC))
                .param("createdAt", OffsetDateTime.ofInstant(fraudCase.updatedAt(), ZoneOffset.UTC)).update();
        if (inserted == 1) return true;
        Existing existing = jdbc.sql("""
                SELECT event_type, snapshot_hash FROM fraud_case.fraud_case_projection_outbox
                WHERE fraud_case_id=:caseId AND aggregate_version=:version
                """).param("caseId", fraudCase.caseId()).param("version", fraudCase.version())
                .query((rs, row) -> new Existing(rs.getString(1), rs.getString(2))).single();
        if (existing.type.equals(type.name()) && existing.hash.equals(event.getSnapshotHash())) return false;
        throw new ProjectionIntegrityException("Different projection exists for case/version "
                + fraudCase.caseId() + "/" + fraudCase.version());
    }

    public boolean appendCreated(UUID caseId, AuthorizationEventSnapshot source, Instant createdAt) {
        FraudCase fraudCase = new FraudCase(caseId, source.sourceEventId(), source.sourceEventHash(),
                source.requestId(), FraudCaseStatus.NEW, null, 0, source.occurredAt(),
                source.cardTokenFingerprint(), source.merchantId(), source.merchantCategoryCode(),
                source.amount(), source.currency(), source.country(), source.channel(),
                source.transactionTime(), source.nonFraudResult(), source.decision(), source.declineReason(),
                source.fraudAssessment(), source.riskScore(), source.caseRequired(), createdAt, createdAt,
                null, null, null, null, source.matchedRules());
        return append(fraudCase, FraudCaseProjectionType.CREATED);
    }
    private record Existing(String type, String hash) {}
}
