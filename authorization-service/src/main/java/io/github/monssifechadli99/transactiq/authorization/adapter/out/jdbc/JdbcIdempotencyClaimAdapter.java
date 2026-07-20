package io.github.monssifechadli99.transactiq.authorization.adapter.out.jdbc;

import io.github.monssifechadli99.transactiq.authorization.application.model.IdempotencyClaimResult;
import io.github.monssifechadli99.transactiq.authorization.application.model.IdempotencyClaimResult.Claimed;
import io.github.monssifechadli99.transactiq.authorization.application.model.IdempotencyClaimResult.Completed;
import io.github.monssifechadli99.transactiq.authorization.application.model.IdempotencyClaimResult.Conflict;
import io.github.monssifechadli99.transactiq.authorization.application.model.IdempotencyClaimResult.Pending;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.IdempotencyClaimPort;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationDecision;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationOutcome;
import io.github.monssifechadli99.transactiq.authorization.domain.DeclineReason;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessment;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcIdempotencyClaimAdapter implements IdempotencyClaimPort {

    private final JdbcClient jdbcClient;
    private final TransactionOperations transactionOperations;

    public JdbcIdempotencyClaimAdapter(
            JdbcClient jdbcClient, TransactionOperations transactionOperations) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.transactionOperations = Objects.requireNonNull(
                transactionOperations, "transactionOperations must not be null");
    }

    @Override
    public IdempotencyClaimResult claim(AuthorizationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        CanonicalRequest canonicalRequest = CanonicalRequest.from(command);
        String fingerprint = fingerprint(canonicalRequest);

        return Objects.requireNonNull(
                transactionOperations.execute(
                        status -> claimWithinTransaction(command, canonicalRequest, fingerprint)),
                "claim transaction returned no result");
    }

    @Override
    public boolean releasePending(UUID requestId) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        return Boolean.TRUE.equals(transactionOperations.execute(status -> jdbcClient.sql(
                        """
                        DELETE FROM "authorization".authorization_requests
                        WHERE request_id = :requestId
                          AND status = 'PENDING'
                        """)
                .param("requestId", requestId)
                .update()
                == 1));
    }

    private IdempotencyClaimResult claimWithinTransaction(
            AuthorizationCommand command,
            CanonicalRequest canonicalRequest,
            String fingerprint) {
        int inserted = jdbcClient.sql(
                        """
                        INSERT INTO "authorization".authorization_requests (
                            request_id,
                            request_fingerprint,
                            request_payload,
                            status
                        ) VALUES (
                            :requestId,
                            :fingerprint,
                            jsonb_build_object(
                                'requestId', :payloadRequestId,
                                'cardToken', :cardToken,
                                'merchantId', :merchantId,
                                'merchantCategoryCode', :merchantCategoryCode,
                                'amount', CAST(:amount AS NUMERIC),
                                'currency', :currency,
                                'country', :country,
                                'channel', :channel,
                                'transactionTime', :transactionTime
                            ),
                            'PENDING'
                        )
                        ON CONFLICT (request_id) DO NOTHING
                        """)
                .param("requestId", command.requestId())
                .param("fingerprint", fingerprint)
                .param("payloadRequestId", canonicalRequest.requestId())
                .param("cardToken", canonicalRequest.cardToken())
                .param("merchantId", canonicalRequest.merchantId())
                .param("merchantCategoryCode", canonicalRequest.merchantCategoryCode())
                .param("amount", canonicalRequest.amount())
                .param("currency", canonicalRequest.currency())
                .param("country", canonicalRequest.country())
                .param("channel", canonicalRequest.channel())
                .param("transactionTime", canonicalRequest.transactionTime())
                .update();

        if (inserted == 1) {
            return new Claimed();
        }

        StoredClaim storedClaim = jdbcClient.sql(
                        """
                        SELECT request.request_fingerprint,
                               request.status,
                               ledger.decision,
                               ledger.decline_reason,
                               ledger.fraud_assessment
                        FROM "authorization".authorization_requests request
                        LEFT JOIN "authorization".authorization_ledger ledger
                          ON ledger.request_id = request.request_id
                        WHERE request.request_id = :requestId
                        """)
                .param("requestId", command.requestId())
                .query((resultSet, rowNumber) -> new StoredClaim(
                        resultSet.getString("request_fingerprint"),
                        resultSet.getString("status"),
                        resultSet.getString("decision"),
                        resultSet.getString("decline_reason"),
                        resultSet.getString("fraud_assessment")))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency claim disappeared during transaction"));

        if (!fingerprint.equals(storedClaim.fingerprint())) {
            return new Conflict();
        }

        return switch (storedClaim.status()) {
            case "PENDING" -> new Pending();
            case "COMPLETED" -> new Completed(reconstructOutcome(storedClaim));
            default -> throw new IllegalStateException(
                    "Unsupported authorization request status: " + storedClaim.status());
        };
    }

    private static AuthorizationOutcome reconstructOutcome(StoredClaim storedClaim) {
        if (storedClaim.decision() == null || storedClaim.fraudAssessment() == null) {
            throw new IllegalStateException("Completed request has no stored ledger outcome");
        }

        AuthorizationDecision decision = AuthorizationDecision.valueOf(storedClaim.decision());
        return switch (decision) {
            case APPROVED -> new AuthorizationOutcome.Approved();
            case DECLINED -> {
                if (storedClaim.declineReason() == null) {
                    throw new IllegalStateException(
                            "Completed declined request has no stored decline reason");
                }
                DeclineReason declineReason = DeclineReason.valueOf(storedClaim.declineReason());
                boolean fraudCaseRequired = FraudAssessment.valueOf(storedClaim.fraudAssessment())
                        != FraudAssessment.CLEAR;
                yield new AuthorizationOutcome.Declined(declineReason, fraudCaseRequired);
            }
        };
    }

    private static String fingerprint(CanonicalRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            addField(digest, "requestId", request.requestId());
            addField(digest, "cardToken", request.cardToken());
            addField(digest, "merchantId", request.merchantId());
            addField(digest, "merchantCategoryCode", request.merchantCategoryCode());
            addField(digest, "amount", request.amount());
            addField(digest, "currency", request.currency());
            addField(digest, "country", request.country());
            addField(digest, "channel", request.channel());
            addField(digest, "transactionTime", request.transactionTime());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void addField(MessageDigest digest, String name, String value) {
        addLengthPrefixed(digest, name);
        addLengthPrefixed(digest, value);
    }

    private static void addLengthPrefixed(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private record CanonicalRequest(
            String requestId,
            String cardToken,
            String merchantId,
            String merchantCategoryCode,
            String amount,
            String currency,
            String country,
            String channel,
            String transactionTime) {

        private static CanonicalRequest from(AuthorizationCommand command) {
            return new CanonicalRequest(
                    Objects.requireNonNull(command.requestId(), "requestId must not be null")
                            .toString(),
                    Objects.requireNonNull(command.cardToken(), "cardToken must not be null"),
                    Objects.requireNonNull(command.merchantId(), "merchantId must not be null"),
                    Objects.requireNonNull(
                            command.merchantCategoryCode(),
                            "merchantCategoryCode must not be null"),
                    Objects.requireNonNull(command.amount(), "amount must not be null")
                            .stripTrailingZeros()
                            .toPlainString(),
                    Objects.requireNonNull(command.currency(), "currency must not be null"),
                    Objects.requireNonNull(command.country(), "country must not be null"),
                    Objects.requireNonNull(command.channel(), "channel must not be null").name(),
                    Objects.requireNonNull(
                                    command.transactionTime(),
                                    "transactionTime must not be null")
                            .toString());
        }
    }

    private record StoredClaim(
            String fingerprint,
            String status,
            String decision,
            String declineReason,
            String fraudAssessment) {}
}
