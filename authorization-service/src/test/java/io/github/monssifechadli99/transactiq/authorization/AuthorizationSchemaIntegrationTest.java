package io.github.monssifechadli99.transactiq.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.monssifechadli99.transactiq.authorization.support.AuthorizationServiceIntegrationTest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@AuthorizationServiceIntegrationTest
class AuthorizationSchemaIntegrationTest {

    private static final UUID FUNDED_ACCOUNT_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String REQUEST_FINGERPRINT = "0".repeat(64);
    private static final OffsetDateTime COMPLETED_AT =
            OffsetDateTime.parse("2026-07-20T10:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsAllTablesRequiredColumnsAndNamedConstraints() {
        List<String> tables = jdbcTemplate.queryForList(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'authorization'
                  AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """,
                String.class);
        assertEquals(
                List.of(
                        "authorization_ledger",
                        "authorization_requests",
                        "balance_reservations",
                        "card_accounts"),
                tables);

        List<String> nullableColumns = jdbcTemplate.queryForList(
                """
                SELECT table_name || '.' || column_name
                FROM information_schema.columns
                WHERE table_schema = 'authorization'
                  AND is_nullable = 'YES'
                ORDER BY table_name, ordinal_position
                """,
                String.class);
        assertEquals(
                List.of(
                        "authorization_ledger.decline_reason",
                        "authorization_requests.completed_at"),
                nullableColumns);

        List<String> constraints = jdbcTemplate.queryForList(
                """
                SELECT constraint_definition.conname
                FROM pg_catalog.pg_constraint constraint_definition
                JOIN pg_catalog.pg_namespace constraint_schema
                  ON constraint_schema.oid = constraint_definition.connamespace
                WHERE constraint_schema.nspname = 'authorization'
                  AND constraint_definition.contype IN ('p', 'u', 'f', 'c')
                ORDER BY constraint_definition.conname
                """,
                String.class);
        assertEquals(
                List.of(
                        "ck_authorization_ledger_decision",
                        "ck_authorization_ledger_decline_reason",
                        "ck_authorization_requests_completion",
                        "ck_authorization_requests_status",
                        "ck_balance_reservations_amount_positive",
                        "ck_balance_reservations_status_active",
                        "ck_card_accounts_posted_balance_non_negative",
                        "ck_card_accounts_reserved_amount_non_negative",
                        "ck_card_accounts_reserved_amount_within_balance",
                        "fk_authorization_ledger_request",
                        "fk_balance_reservations_account",
                        "fk_balance_reservations_request",
                        "pk_authorization_ledger",
                        "pk_authorization_requests",
                        "pk_balance_reservations",
                        "pk_card_accounts",
                        "uk_balance_reservations_request",
                        "uk_card_accounts_card_token"),
                constraints);
    }

    @Test
    void seedsOnlyDocumentedSyntheticEurAccounts() {
        assertEquals(
                2,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM \"authorization\".card_accounts",
                        Integer.class));
        assertEquals(
                new AccountBalance("EUR", new BigDecimal("1000.00"), new BigDecimal("0.00")),
                accountBalance("tok_A1B2C3D4"));
        assertEquals(
                new AccountBalance("EUR", new BigDecimal("0.00"), new BigDecimal("0.00")),
                accountBalance("tok_insufficient01"));
    }

    @Test
    void rejectsDuplicateCardTokens() {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertAccount(
                        UUID.randomUUID(),
                        "tok_A1B2C3D4",
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidAccountBalances")
    void rejectsNegativeBalancesAndInvalidReservedAmounts(
            String description,
            String cardToken,
            BigDecimal postedBalance,
            BigDecimal reservedAmount) {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertAccount(
                        UUID.randomUUID(), cardToken, postedBalance, reservedAmount));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequestStates")
    void rejectsInvalidRequestStatusAndTimestampCombinations(
            String description, String status, OffsetDateTime completedAt) {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertAuthorizationRequest(UUID.randomUUID(), status, completedAt));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidLedgerOutcomes")
    void rejectsInvalidApprovedAndDeclinedReasonCombinations(
            String description, String decision, String declineReason) {
        UUID requestId = UUID.randomUUID();
        insertAuthorizationRequest(requestId, "COMPLETED", COMPLETED_AT);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertLedger(requestId, decision, declineReason));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidReservationStates")
    void rejectsNonPositiveOrInactiveReservations(
            String description, BigDecimal amount, String status) {
        UUID requestId = createApprovedLedgerEntry();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertReservation(
                        UUID.randomUUID(), requestId, FUNDED_ACCOUNT_ID, amount, status));
    }

    @Test
    void rejectsLedgerWithoutAuthorizationRequest() {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertLedger(UUID.randomUUID(), "APPROVED", null));
    }

    @Test
    void rejectsReservationWithoutLedgerRequest() {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertReservation(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        FUNDED_ACCOUNT_ID,
                        new BigDecimal("25.00"),
                        "ACTIVE"));
    }

    @Test
    void rejectsReservationWithoutCardAccount() {
        UUID requestId = createApprovedLedgerEntry();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertReservation(
                        UUID.randomUUID(),
                        requestId,
                        UUID.randomUUID(),
                        new BigDecimal("25.00"),
                        "ACTIVE"));
    }

    @Test
    void rejectsSecondReservationForSameRequest() {
        UUID requestId = createApprovedLedgerEntry();
        insertReservation(
                UUID.randomUUID(),
                requestId,
                FUNDED_ACCOUNT_ID,
                new BigDecimal("25.00"),
                "ACTIVE");

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertReservation(
                        UUID.randomUUID(),
                        requestId,
                        FUNDED_ACCOUNT_ID,
                        new BigDecimal("10.00"),
                        "ACTIVE"));
    }

    private AccountBalance accountBalance(String cardToken) {
        return jdbcTemplate.queryForObject(
                """
                SELECT currency, posted_balance, reserved_amount
                FROM "authorization".card_accounts
                WHERE card_token = ?
                """,
                (resultSet, rowNumber) -> new AccountBalance(
                        resultSet.getString("currency"),
                        resultSet.getBigDecimal("posted_balance"),
                        resultSet.getBigDecimal("reserved_amount")),
                cardToken);
    }

    private void insertAccount(
            UUID accountId,
            String cardToken,
            BigDecimal postedBalance,
            BigDecimal reservedAmount) {
        jdbcTemplate.update(
                """
                INSERT INTO "authorization".card_accounts (
                    account_id, card_token, currency, posted_balance, reserved_amount
                ) VALUES (?, ?, 'EUR', ?, ?)
                """,
                accountId,
                cardToken,
                postedBalance,
                reservedAmount);
    }

    private void insertAuthorizationRequest(
            UUID requestId, String status, OffsetDateTime completedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO "authorization".authorization_requests (
                    request_id, request_fingerprint, request_payload, status, completed_at
                ) VALUES (?, ?, CAST(? AS JSONB), ?, ?)
                """,
                requestId,
                REQUEST_FINGERPRINT,
                "{}",
                status,
                completedAt);
    }

    private void insertLedger(UUID requestId, String decision, String declineReason) {
        jdbcTemplate.update(
                """
                INSERT INTO "authorization".authorization_ledger (
                    request_id,
                    decision,
                    decline_reason,
                    fraud_assessment,
                    non_fraud_check_result
                ) VALUES (?, ?, ?, 'CLEAR', 'PASSED')
                """,
                requestId,
                decision,
                declineReason);
    }

    private void insertReservation(
            UUID reservationId,
            UUID requestId,
            UUID accountId,
            BigDecimal amount,
            String status) {
        jdbcTemplate.update(
                """
                INSERT INTO "authorization".balance_reservations (
                    reservation_id, request_id, account_id, amount, currency, status
                ) VALUES (?, ?, ?, ?, 'EUR', ?)
                """,
                reservationId,
                requestId,
                accountId,
                amount,
                status);
    }

    private UUID createApprovedLedgerEntry() {
        UUID requestId = UUID.randomUUID();
        insertAuthorizationRequest(requestId, "COMPLETED", COMPLETED_AT);
        insertLedger(requestId, "APPROVED", null);
        return requestId;
    }

    private static Stream<Arguments> invalidAccountBalances() {
        return Stream.of(
                Arguments.of(
                        "negative posted balance",
                        "tok_negativePosted01",
                        new BigDecimal("-0.01"),
                        BigDecimal.ZERO),
                Arguments.of(
                        "negative reserved amount",
                        "tok_negativeReserved01",
                        new BigDecimal("100.00"),
                        new BigDecimal("-0.01")),
                Arguments.of(
                        "reserved amount greater than posted balance",
                        "tok_excessReserved01",
                        new BigDecimal("100.00"),
                        new BigDecimal("100.01")));
    }

    private static Stream<Arguments> invalidRequestStates() {
        return Stream.of(
                Arguments.of("unsupported request status", "FAILED", null),
                Arguments.of("pending request with completion time", "PENDING", COMPLETED_AT),
                Arguments.of("completed request without completion time", "COMPLETED", null));
    }

    private static Stream<Arguments> invalidLedgerOutcomes() {
        return Stream.of(
                Arguments.of(
                        "approved decision with decline reason",
                        "APPROVED",
                        "INSUFFICIENT_FUNDS"),
                Arguments.of("declined decision without decline reason", "DECLINED", null),
                Arguments.of("unsupported decision", "REVIEW", null));
    }

    private static Stream<Arguments> invalidReservationStates() {
        return Stream.of(
                Arguments.of("zero reservation amount", BigDecimal.ZERO, "ACTIVE"),
                Arguments.of(
                        "negative reservation amount", new BigDecimal("-0.01"), "ACTIVE"),
                Arguments.of(
                        "unsupported reservation status", new BigDecimal("10.00"), "RELEASED"));
    }

    private record AccountBalance(
            String currency, BigDecimal postedBalance, BigDecimal reservedAmount) {}
}
