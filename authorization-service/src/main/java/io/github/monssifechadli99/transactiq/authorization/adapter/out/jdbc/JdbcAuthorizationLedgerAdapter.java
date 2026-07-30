package io.github.monssifechadli99.transactiq.authorization.adapter.out.jdbc;

import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.AuthorizationLedgerPort;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationOutcome;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessmentResult;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudRuleMatch;
import io.github.monssifechadli99.transactiq.authorization.domain.NonFraudCheckResult;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcAuthorizationLedgerAdapter implements AuthorizationLedgerPort {

    private final JdbcClient jdbcClient;

    public JdbcAuthorizationLedgerAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public void record(
            AuthorizationCommand command,
            FraudAssessmentResult fraudAssessment,
            NonFraudCheckResult nonFraudCheckResult,
            AuthorizationOutcome outcome) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(fraudAssessment, "fraudAssessment must not be null");
        Objects.requireNonNull(nonFraudCheckResult, "nonFraudCheckResult must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");

        if (outcome instanceof AuthorizationOutcome.Approved) {
            UUID accountId = accountId(command);
            increaseReservedAmount(command, accountId);
            insertApprovedLedger(command, fraudAssessment, nonFraudCheckResult);
            insertReservation(command, accountId);
        } else if (outcome instanceof AuthorizationOutcome.Declined declined) {
            insertDeclinedLedger(
                    command, fraudAssessment, nonFraudCheckResult, declined);
        }

        insertFraudRuleMatches(command.requestId(), fraudAssessment);
        markRequestCompleted(command.requestId());
    }

    private UUID accountId(AuthorizationCommand command) {
        return jdbcClient.sql(
                        """
                        SELECT account_id
                        FROM "authorization".card_accounts
                        WHERE card_token = :cardToken
                          AND currency = :currency
                        """)
                .param("cardToken", command.cardToken())
                .param("currency", command.currency())
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Approved authorization has no matching card account"));
    }

    private void increaseReservedAmount(AuthorizationCommand command, UUID accountId) {
        int updated = jdbcClient.sql(
                        """
                        UPDATE "authorization".card_accounts
                        SET reserved_amount = reserved_amount + :amount,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE account_id = :accountId
                          AND card_token = :cardToken
                          AND currency = :currency
                          AND reserved_amount + :amount <= posted_balance
                        """)
                .param("amount", command.amount())
                .param("accountId", accountId)
                .param("cardToken", command.cardToken())
                .param("currency", command.currency())
                .update();
        requireExactlyOne(updated, "increase reserved amount");
    }

    private void insertApprovedLedger(
            AuthorizationCommand command,
            FraudAssessmentResult fraudAssessment,
            NonFraudCheckResult nonFraudCheckResult) {
        int inserted = jdbcClient.sql(
                        """
                        INSERT INTO "authorization".authorization_ledger (
                            request_id,
                            decision,
                            decline_reason,
                            fraud_assessment,
                            risk_score,
                            non_fraud_check_result
                        ) VALUES (
                            :requestId,
                            'APPROVED',
                            NULL,
                            :fraudAssessment,
                            :riskScore,
                            :nonFraudCheckResult
                        )
                        """)
                .param("requestId", command.requestId())
                .param("fraudAssessment", fraudAssessment.assessment().name())
                .param("riskScore", fraudAssessment.riskScore())
                .param("nonFraudCheckResult", nonFraudCheckResult.name())
                .update();
        requireExactlyOne(inserted, "insert approved ledger entry");
    }

    private void insertDeclinedLedger(
            AuthorizationCommand command,
            FraudAssessmentResult fraudAssessment,
            NonFraudCheckResult nonFraudCheckResult,
            AuthorizationOutcome.Declined declined) {
        int inserted = jdbcClient.sql(
                        """
                        INSERT INTO "authorization".authorization_ledger (
                            request_id,
                            decision,
                            decline_reason,
                            fraud_assessment,
                            risk_score,
                            non_fraud_check_result
                        ) VALUES (
                            :requestId,
                            'DECLINED',
                            :declineReason,
                            :fraudAssessment,
                            :riskScore,
                            :nonFraudCheckResult
                        )
                        """)
                .param("requestId", command.requestId())
                .param("declineReason", declined.declineReason().name())
                .param("fraudAssessment", fraudAssessment.assessment().name())
                .param("riskScore", fraudAssessment.riskScore())
                .param("nonFraudCheckResult", nonFraudCheckResult.name())
                .update();
        requireExactlyOne(inserted, "insert declined ledger entry");
    }

    private void insertFraudRuleMatches(
            UUID requestId, FraudAssessmentResult fraudAssessment) {
        for (int matchOrder = 0;
                matchOrder < fraudAssessment.matchedRules().size();
                matchOrder++) {
            FraudRuleMatch match = fraudAssessment.matchedRules().get(matchOrder);
            int inserted = jdbcClient.sql(
                            """
                            INSERT INTO "authorization".fraud_rule_matches (
                                request_id,
                                match_order,
                                rule_code,
                                severity,
                                evidence,
                                score_contribution
                            ) VALUES (
                                :requestId,
                                :matchOrder,
                                :ruleCode,
                                :severity,
                                :evidence,
                                :scoreContribution
                            )
                            """)
                    .param("requestId", requestId)
                    .param("matchOrder", matchOrder)
                    .param("ruleCode", match.ruleCode())
                    .param("severity", match.severity().name())
                    .param("evidence", match.evidence())
                    .param("scoreContribution", match.scoreContribution())
                    .update();
            requireExactlyOne(inserted, "insert fraud rule match");
        }
    }

    private void insertReservation(AuthorizationCommand command, UUID accountId) {
        int inserted = jdbcClient.sql(
                        """
                        INSERT INTO "authorization".balance_reservations (
                            reservation_id,
                            request_id,
                            account_id,
                            amount,
                            currency,
                            status
                        ) VALUES (
                            :reservationId,
                            :requestId,
                            :accountId,
                            :amount,
                            :currency,
                            'ACTIVE'
                        )
                        """)
                .param("reservationId", UUID.randomUUID())
                .param("requestId", command.requestId())
                .param("accountId", accountId)
                .param("amount", command.amount())
                .param("currency", command.currency())
                .update();
        requireExactlyOne(inserted, "insert active balance reservation");
    }

    private void markRequestCompleted(UUID requestId) {
        int updated = jdbcClient.sql(
                        """
                        UPDATE "authorization".authorization_requests
                        SET status = 'COMPLETED',
                            completed_at = CURRENT_TIMESTAMP
                        WHERE request_id = :requestId
                          AND status = 'PENDING'
                        """)
                .param("requestId", requestId)
                .update();
        requireExactlyOne(updated, "complete pending authorization request");
    }

    private static void requireExactlyOne(int affectedRows, String operation) {
        if (affectedRows != 1) {
            throw new IllegalStateException(
                    "Expected one row to " + operation + " but affected " + affectedRows);
        }
    }
}
