package io.github.monssifechadli99.transactiq.case_management.adapter.out.jdbc;

import io.github.monssifechadli99.transactiq.case_management.application.model.FraudCaseClaimResult;
import io.github.monssifechadli99.transactiq.case_management.application.model.FraudCaseClaimResult.Outcome;
import io.github.monssifechadli99.transactiq.case_management.application.model.FraudCaseQuery;
import io.github.monssifechadli99.transactiq.case_management.application.model.FraudCaseResolutionResult;
import io.github.monssifechadli99.transactiq.case_management.application.port.out.FraudCaseLifecycleStore;
import io.github.monssifechadli99.transactiq.case_management.domain.AuthorizationDecision;
import io.github.monssifechadli99.transactiq.case_management.domain.DeclineReason;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudAssessment;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCase;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseAssignmentFilter;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseClaimPolicy;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseStatus;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseLifecycleEvent;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseResolutionOutcome;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseResolutionPolicy;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseSummary;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudRuleSeverity;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudRuleSnapshot;
import io.github.monssifechadli99.transactiq.case_management.domain.NonFraudResult;
import io.github.monssifechadli99.transactiq.case_management.domain.TransactionChannel;
import io.github.monssifechadli99.transactiq.case_management.projection.FraudCaseProjectionOutbox;
import io.github.monssifechadli99.transactiq.case_management.projection.FraudCaseProjectionType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcFraudCaseLifecycleStore implements FraudCaseLifecycleStore {
    private final JdbcClient jdbcClient;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final Supplier<UUID> lifecycleEventIdSupplier;
    private final FraudCaseClaimPolicy claimPolicy;
    private final FraudCaseResolutionPolicy resolutionPolicy;
    private final FraudCaseProjectionOutbox projectionOutbox;

    public JdbcFraudCaseLifecycleStore(
            JdbcClient jdbcClient,
            TransactionOperations transactions,
            Clock clock,
            Supplier<UUID> lifecycleEventIdSupplier,
            FraudCaseClaimPolicy claimPolicy,
            FraudCaseResolutionPolicy resolutionPolicy,
            FraudCaseProjectionOutbox projectionOutbox) {
        this.jdbcClient = jdbcClient;
        this.transactions = transactions;
        this.clock = clock;
        this.lifecycleEventIdSupplier = lifecycleEventIdSupplier;
        this.claimPolicy = claimPolicy;
        this.resolutionPolicy = resolutionPolicy;
        this.projectionOutbox = projectionOutbox;
    }

    @Override
    public List<FraudCaseSummary> findPage(FraudCaseQuery query) {
        StringBuilder sql = new StringBuilder("""
                SELECT case_id, status, assignee_id, version, fraud_assessment, risk_score,
                       authorization_decision, amount, currency, merchant_id, occurred_at,
                       created_at, updated_at, resolution_outcome
                FROM fraud_case.fraud_cases
                WHERE 1 = 1
                """);
        List<Parameter> parameters = new ArrayList<>();
        if (query.status() != null) {
            sql.append(" AND status = :status");
            parameters.add(new Parameter("status", query.status().name()));
        }
        switch (query.assignment()) {
            case ANY -> { }
            case UNASSIGNED -> sql.append(" AND assignee_id IS NULL");
            case ASSIGNED -> sql.append(" AND assignee_id IS NOT NULL");
            case MINE -> {
                sql.append(" AND assignee_id = :analystId");
                parameters.add(new Parameter("analystId", query.analystId()));
            }
        }
        if (query.afterCreatedAt() != null) {
            sql.append(" AND (created_at, case_id) > (:afterCreatedAt, :afterCaseId)");
            parameters.add(new Parameter("afterCreatedAt", databaseTimestamp(query.afterCreatedAt())));
            parameters.add(new Parameter("afterCaseId", query.afterCaseId()));
        }
        sql.append(" ORDER BY created_at ASC, case_id ASC LIMIT :limit");
        parameters.add(new Parameter("limit", query.pageSize()));
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql.toString());
        for (Parameter parameter : parameters) {
            statement = statement.param(parameter.name(), parameter.value());
        }
        return statement.query((rs, row) -> mapSummary(rs)).list();
    }

    @Override
    public Optional<FraudCase> findById(UUID caseId) {
        List<FraudCase> cases = jdbcClient.sql("""
                        SELECT * FROM fraud_case.fraud_cases WHERE case_id = :caseId
                        """)
                .param("caseId", caseId)
                .query((rs, row) -> mapCase(rs, findRules(caseId)))
                .list();
        return cases.stream().findFirst();
    }

    @Override
    public FraudCaseClaimResult claim(UUID caseId, String analystId, long expectedVersion) {
        FraudCaseClaimResult result = transactions.execute(status ->
                claimInTransaction(caseId, analystId, expectedVersion));
        return Objects.requireNonNull(result, "claim transaction must return a result");
    }

    @Override
    public FraudCaseResolutionResult resolve(
            UUID caseId, String analystId, long expectedVersion,
            FraudCaseResolutionOutcome outcome, String rationale) {
        FraudCaseResolutionResult result = transactions.execute(status ->
                resolveInTransaction(caseId, analystId, expectedVersion, outcome, rationale));
        return Objects.requireNonNull(result, "resolution transaction must return a result");
    }

    private FraudCaseResolutionResult resolveInTransaction(
            UUID caseId, String analystId, long expectedVersion,
            FraudCaseResolutionOutcome outcome, String rationale) {
        Instant now = clock.instant();
        int updated = jdbcClient.sql("""
                        UPDATE fraud_case.fraud_cases
                        SET status = 'RESOLVED',
                            resolution_outcome = :outcome,
                            resolution_rationale = :rationale,
                            resolved_at = :resolvedAt,
                            resolved_by = :analystId,
                            version = version + 1,
                            updated_at = :resolvedAt
                        WHERE case_id = :caseId
                          AND status = 'IN_REVIEW'
                          AND assignee_id = :analystId
                          AND version = :expectedVersion
                          AND version < 9223372036854775807
                        """)
                .param("outcome", outcome.name())
                .param("rationale", rationale)
                .param("resolvedAt", databaseTimestamp(now))
                .param("analystId", analystId)
                .param("caseId", caseId)
                .param("expectedVersion", expectedVersion)
                .update();
        if (updated == 1) {
            long resultingVersion = expectedVersion + 1;
            int inserted = jdbcClient.sql("""
                            INSERT INTO fraud_case.fraud_case_lifecycle_events (
                                lifecycle_event_id, fraud_case_id, event_type,
                                previous_status, resulting_status,
                                previous_assignee_id, resulting_assignee_id,
                                actor_id, case_version, occurred_at,
                                resolution_outcome, resolution_rationale
                            ) VALUES (
                                :eventId, :caseId, 'RESOLVED', 'IN_REVIEW', 'RESOLVED',
                                :analystId, :analystId, :analystId, :caseVersion, :occurredAt,
                                :outcome, :rationale
                            )
                            """)
                    .param("eventId", lifecycleEventIdSupplier.get())
                    .param("caseId", caseId)
                    .param("analystId", analystId)
                    .param("caseVersion", resultingVersion)
                    .param("occurredAt", databaseTimestamp(now))
                    .param("outcome", outcome.name())
                    .param("rationale", rationale)
                    .update();
            if (inserted != 1) {
                throw new IllegalStateException("Expected one resolution event to be inserted");
            }
            FraudCase resulting = requiredCase(caseId);
            projectionOutbox.append(resulting, FraudCaseProjectionType.RESOLVED);
            return new FraudCaseResolutionResult(FraudCaseResolutionResult.Outcome.RESOLVED, resulting);
        }

        Optional<FraudCase> existing = findById(caseId);
        if (existing.isEmpty()) {
            return new FraudCaseResolutionResult(
                    FraudCaseResolutionResult.Outcome.NOT_FOUND, null);
        }
        FraudCase fraudCase = existing.get();
        FraudCaseResolutionResult.Outcome classified = switch (resolutionPolicy.classify(
                fraudCase, analystId, expectedVersion, outcome, rationale)) {
            case RESOLVABLE -> throw new IllegalStateException("Resolvable case failed atomic update");
            case ALREADY_RESOLVED_IDENTICALLY ->
                    FraudCaseResolutionResult.Outcome.ALREADY_RESOLVED_IDENTICALLY;
            case ALREADY_RESOLVED_DIFFERENTLY ->
                    FraudCaseResolutionResult.Outcome.ALREADY_RESOLVED_DIFFERENTLY;
            case NOT_IN_REVIEW -> FraudCaseResolutionResult.Outcome.NOT_IN_REVIEW;
            case NOT_ASSIGNED_TO_ANALYST ->
                    FraudCaseResolutionResult.Outcome.NOT_ASSIGNED_TO_ANALYST;
            case VERSION_CONFLICT -> FraudCaseResolutionResult.Outcome.VERSION_CONFLICT;
        };
        return new FraudCaseResolutionResult(classified, fraudCase);
    }

    @Override
    public Optional<List<FraudCaseLifecycleEvent>> findHistory(UUID caseId) {
        if (jdbcClient.sql("SELECT count(*) FROM fraud_case.fraud_cases WHERE case_id = :caseId")
                .param("caseId", caseId).query(Integer.class).single() == 0) {
            return Optional.empty();
        }
        List<FraudCaseLifecycleEvent> events = jdbcClient.sql("""
                        SELECT lifecycle_event_id, event_type, previous_status, resulting_status,
                               previous_assignee_id, resulting_assignee_id, actor_id,
                               case_version, occurred_at, resolution_outcome, resolution_rationale
                        FROM fraud_case.fraud_case_lifecycle_events
                        WHERE fraud_case_id = :caseId
                        ORDER BY case_version ASC, lifecycle_event_id ASC
                        """)
                .param("caseId", caseId)
                .query((rs, row) -> new FraudCaseLifecycleEvent(
                        rs.getObject("lifecycle_event_id", UUID.class),
                        rs.getString("event_type"),
                        FraudCaseStatus.valueOf(rs.getString("previous_status")),
                        FraudCaseStatus.valueOf(rs.getString("resulting_status")),
                        rs.getString("previous_assignee_id"),
                        rs.getString("resulting_assignee_id"),
                        rs.getString("actor_id"),
                        rs.getLong("case_version"),
                        instant(rs, "occurred_at"),
                        nullableResolutionOutcome(rs.getString("resolution_outcome")),
                        rs.getString("resolution_rationale")))
                .list();
        return Optional.of(events);
    }

    private FraudCaseClaimResult claimInTransaction(
            UUID caseId, String analystId, long expectedVersion) {
        Instant now = clock.instant();
        int updated = jdbcClient.sql("""
                        UPDATE fraud_case.fraud_cases
                        SET status = 'IN_REVIEW',
                            assignee_id = :analystId,
                            version = version + 1,
                            updated_at = :updatedAt
                        WHERE case_id = :caseId
                          AND status = 'NEW'
                          AND assignee_id IS NULL
                          AND version = :expectedVersion
                        """)
                .param("analystId", analystId)
                .param("updatedAt", databaseTimestamp(now))
                .param("caseId", caseId)
                .param("expectedVersion", expectedVersion)
                .update();
        if (updated == 1) {
            long resultingVersion = expectedVersion + 1;
            int inserted = jdbcClient.sql("""
                            INSERT INTO fraud_case.fraud_case_lifecycle_events (
                                lifecycle_event_id, fraud_case_id, event_type,
                                previous_status, resulting_status,
                                previous_assignee_id, resulting_assignee_id,
                                actor_id, case_version, occurred_at
                            ) VALUES (
                                :eventId, :caseId, 'CLAIMED', 'NEW', 'IN_REVIEW',
                                NULL, :analystId, :analystId, :caseVersion, :occurredAt
                            )
                            """)
                    .param("eventId", lifecycleEventIdSupplier.get())
                    .param("caseId", caseId)
                    .param("analystId", analystId)
                    .param("caseVersion", resultingVersion)
                    .param("occurredAt", databaseTimestamp(now))
                    .update();
            if (inserted != 1) {
                throw new IllegalStateException("Expected one lifecycle event to be inserted");
            }
            FraudCase resulting = requiredCase(caseId);
            projectionOutbox.append(resulting, FraudCaseProjectionType.CLAIMED);
            return new FraudCaseClaimResult(Outcome.CLAIMED, resulting);
        }

        Optional<FraudCase> existing = findById(caseId);
        if (existing.isEmpty()) {
            return new FraudCaseClaimResult(Outcome.NOT_FOUND, null);
        }
        FraudCase fraudCase = existing.get();
        Outcome outcome = switch (claimPolicy.classify(fraudCase, analystId, expectedVersion)) {
            case CLAIMABLE -> throw new IllegalStateException("Claimable case failed atomic update");
            case ALREADY_CLAIMED_BY_ANALYST -> Outcome.ALREADY_CLAIMED_BY_ANALYST;
            case ALREADY_ASSIGNED -> Outcome.ALREADY_ASSIGNED;
            case VERSION_CONFLICT -> Outcome.VERSION_CONFLICT;
            case NOT_CLAIMABLE -> Outcome.NOT_CLAIMABLE;
        };
        return new FraudCaseClaimResult(outcome, fraudCase);
    }

    private FraudCase requiredCase(UUID caseId) {
        return findById(caseId).orElseThrow(
                () -> new IllegalStateException("Claimed fraud case was not found"));
    }

    private List<FraudRuleSnapshot> findRules(UUID caseId) {
        return jdbcClient.sql("""
                        SELECT rule_code, severity, evidence, score_contribution
                        FROM fraud_case.fraud_case_rule_matches
                        WHERE case_id = :caseId
                        ORDER BY match_order ASC
                        """)
                .param("caseId", caseId)
                .query((rs, row) -> new FraudRuleSnapshot(
                        rs.getString("rule_code"),
                        FraudRuleSeverity.valueOf(rs.getString("severity")),
                        rs.getString("evidence"),
                        rs.getInt("score_contribution")))
                .list();
    }

    private static FraudCaseSummary mapSummary(ResultSet rs) throws SQLException {
        return new FraudCaseSummary(
                rs.getObject("case_id", UUID.class),
                FraudCaseStatus.valueOf(rs.getString("status")),
                rs.getString("assignee_id"),
                rs.getLong("version"),
                FraudAssessment.valueOf(rs.getString("fraud_assessment")),
                rs.getInt("risk_score"),
                AuthorizationDecision.valueOf(rs.getString("authorization_decision")),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("merchant_id"),
                instant(rs, "occurred_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                nullableResolutionOutcome(rs.getString("resolution_outcome")));
    }

    private static FraudCase mapCase(ResultSet rs, List<FraudRuleSnapshot> rules) throws SQLException {
        String declineReason = rs.getString("decline_reason");
        return new FraudCase(
                rs.getObject("case_id", UUID.class),
                rs.getObject("source_event_id", UUID.class),
                rs.getString("source_event_hash"),
                rs.getObject("request_id", UUID.class),
                FraudCaseStatus.valueOf(rs.getString("status")),
                rs.getString("assignee_id"),
                rs.getLong("version"),
                exactInstant(rs, "occurred_at", "occurred_at_nanos"),
                rs.getString("card_token_fingerprint"),
                rs.getString("merchant_id"),
                rs.getString("merchant_category_code"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("country"),
                TransactionChannel.valueOf(rs.getString("channel")),
                exactInstant(rs, "transaction_time", "transaction_time_nanos"),
                NonFraudResult.valueOf(rs.getString("non_fraud_result")),
                AuthorizationDecision.valueOf(rs.getString("authorization_decision")),
                declineReason == null ? null : DeclineReason.valueOf(declineReason),
                FraudAssessment.valueOf(rs.getString("fraud_assessment")),
                rs.getInt("risk_score"),
                rs.getBoolean("case_required"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                nullableResolutionOutcome(rs.getString("resolution_outcome")),
                rs.getString("resolution_rationale"),
                nullableInstant(rs, "resolved_at"),
                rs.getString("resolved_by"),
                rules);
    }

    private static Instant exactInstant(ResultSet rs, String timestamp, String nanos)
            throws SQLException {
        Instant value = instant(rs, timestamp);
        return Instant.ofEpochSecond(value.getEpochSecond(), rs.getInt(nanos));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static FraudCaseResolutionOutcome nullableResolutionOutcome(String value) {
        return value == null ? null : FraudCaseResolutionOutcome.valueOf(value);
    }

    private static OffsetDateTime databaseTimestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record Parameter(String name, Object value) {}
}
