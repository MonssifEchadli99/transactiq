package io.github.monssifechadli99.transactiq.case_management.adapter.out.jdbc;

import io.github.monssifechadli99.transactiq.case_management.application.port.out.FraudCaseStore;
import io.github.monssifechadli99.transactiq.case_management.domain.AuthorizationEventConflictException;
import io.github.monssifechadli99.transactiq.case_management.domain.AuthorizationEventSnapshot;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseStatus;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudRuleSnapshot;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcFraudCaseStore implements FraudCaseStore {

    private final JdbcClient jdbcClient;
    private final TransactionOperations transactionOperations;
    private final Clock clock;
    private final Supplier<UUID> caseIdSupplier;

    public JdbcFraudCaseStore(
            JdbcClient jdbcClient,
            TransactionOperations transactionOperations,
            Clock clock,
            Supplier<UUID> caseIdSupplier) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.transactionOperations = Objects.requireNonNull(
                transactionOperations, "transactionOperations must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.caseIdSupplier = Objects.requireNonNull(
                caseIdSupplier, "caseIdSupplier must not be null");
    }

    @Override
    public CreationResult create(AuthorizationEventSnapshot event) {
        Objects.requireNonNull(event, "event must not be null");
        CreationResult result = transactionOperations.execute(status -> createInTransaction(event));
        return Objects.requireNonNull(result, "case creation transaction must return a result");
    }

    private CreationResult createInTransaction(AuthorizationEventSnapshot event) {
        OffsetDateTime createdAt = databaseTimestamp(clock.instant());
        UUID caseId = Objects.requireNonNull(
                caseIdSupplier.get(), "caseIdSupplier must not return null");
        int inserted = jdbcClient.sql(
                        """
                        INSERT INTO fraud_case.fraud_cases (
                            case_id,
                            source_event_id,
                            source_event_hash,
                            request_id,
                            status,
                            assignee_id,
                            occurred_at,
                            occurred_at_nanos,
                            card_token_fingerprint,
                            merchant_id,
                            merchant_category_code,
                            amount,
                            currency,
                            country,
                            channel,
                            transaction_time,
                            transaction_time_nanos,
                            non_fraud_result,
                            authorization_decision,
                            decline_reason,
                            fraud_assessment,
                            risk_score,
                            case_required,
                            created_at,
                            version,
                            updated_at
                        ) VALUES (
                            :caseId,
                            :sourceEventId,
                            :sourceEventHash,
                            :requestId,
                            :status,
                            NULL,
                            :occurredAt,
                            :occurredAtNanos,
                            :cardTokenFingerprint,
                            :merchantId,
                            :merchantCategoryCode,
                            :amount,
                            :currency,
                            :country,
                            :channel,
                            :transactionTime,
                            :transactionTimeNanos,
                            :nonFraudResult,
                            :authorizationDecision,
                            :declineReason,
                            :fraudAssessment,
                            :riskScore,
                            :caseRequired,
                            :createdAt,
                            0,
                            :createdAt
                        )
                        ON CONFLICT DO NOTHING
                        """)
                .param("caseId", caseId)
                .param("sourceEventId", event.sourceEventId())
                .param("sourceEventHash", event.sourceEventHash())
                .param("requestId", event.requestId())
                .param("status", FraudCaseStatus.NEW.name())
                .param("occurredAt", databaseTimestamp(event.occurredAt()))
                .param("occurredAtNanos", event.occurredAt().getNano())
                .param("cardTokenFingerprint", event.cardTokenFingerprint())
                .param("merchantId", event.merchantId())
                .param("merchantCategoryCode", event.merchantCategoryCode())
                .param("amount", event.amount())
                .param("currency", event.currency())
                .param("country", event.country())
                .param("channel", event.channel().name())
                .param("transactionTime", databaseTimestamp(event.transactionTime()))
                .param("transactionTimeNanos", event.transactionTime().getNano())
                .param("nonFraudResult", event.nonFraudResult().name())
                .param("authorizationDecision", event.decision().name())
                .param("declineReason", event.declineReason() == null
                        ? null
                        : event.declineReason().name())
                .param("fraudAssessment", event.fraudAssessment().name())
                .param("riskScore", event.riskScore())
                .param("caseRequired", event.caseRequired())
                .param("createdAt", createdAt)
                .update();

        if (inserted == 1) {
            insertRuleSnapshots(caseId, event.matchedRules());
            return CreationResult.CREATED;
        }
        return resolveExisting(event);
    }

    private void insertRuleSnapshots(UUID caseId, List<FraudRuleSnapshot> rules) {
        for (int matchOrder = 0; matchOrder < rules.size(); matchOrder++) {
            FraudRuleSnapshot rule = rules.get(matchOrder);
            int inserted = jdbcClient.sql(
                            """
                            INSERT INTO fraud_case.fraud_case_rule_matches (
                                case_id,
                                match_order,
                                rule_code,
                                severity,
                                evidence,
                                score_contribution
                            ) VALUES (
                                :caseId,
                                :matchOrder,
                                :ruleCode,
                                :severity,
                                :evidence,
                                :scoreContribution
                            )
                            """)
                    .param("caseId", caseId)
                    .param("matchOrder", matchOrder)
                    .param("ruleCode", rule.ruleCode())
                    .param("severity", rule.severity().name())
                    .param("evidence", rule.evidence())
                    .param("scoreContribution", rule.scoreContribution())
                    .update();
            if (inserted != 1) {
                throw new IllegalStateException("Expected one fraud-case rule row to be inserted");
            }
        }
    }

    private CreationResult resolveExisting(AuthorizationEventSnapshot event) {
        List<ExistingIdentity> identities = jdbcClient.sql(
                        """
                        SELECT source_event_id, source_event_hash, request_id
                        FROM fraud_case.fraud_cases
                        WHERE source_event_id = :sourceEventId
                           OR request_id = :requestId
                        """)
                .param("sourceEventId", event.sourceEventId())
                .param("requestId", event.requestId())
                .query((resultSet, rowNumber) -> new ExistingIdentity(
                        resultSet.getObject("source_event_id", UUID.class),
                        resultSet.getString("source_event_hash"),
                        resultSet.getObject("request_id", UUID.class)))
                .list();

        if (identities.size() == 1) {
            ExistingIdentity existing = identities.getFirst();
            if (existing.sourceEventId().equals(event.sourceEventId())
                    && existing.sourceEventHash().equals(event.sourceEventHash())
                    && existing.requestId().equals(event.requestId())) {
                return CreationResult.ALREADY_EXISTS;
            }
        }
        throw new AuthorizationEventConflictException(
                "Authorization event identity conflicts with an existing fraud case");
    }

    private static OffsetDateTime databaseTimestamp(java.time.Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record ExistingIdentity(
            UUID sourceEventId, String sourceEventHash, UUID requestId) {}
}
