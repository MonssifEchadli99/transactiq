package io.github.monssifechadli99.transactiq.authorization.adapter.out.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationChannel;
import io.github.monssifechadli99.transactiq.authorization.application.model.PreAuthorizationRejectionException;
import io.github.monssifechadli99.transactiq.authorization.application.model.PreAuthorizationRejectionException.Reason;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.NonFraudCheckPort;
import io.github.monssifechadli99.transactiq.authorization.domain.NonFraudCheckResult;
import io.github.monssifechadli99.transactiq.authorization.support.AuthorizationServiceIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@AuthorizationServiceIntegrationTest
class JdbcNonFraudCheckAdapterIntegrationTest {

    @Autowired
    private NonFraudCheckPort nonFraudCheckPort;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void runtimePortUsesJdbcAdapter() {
        assertInstanceOf(JdbcNonFraudCheckAdapter.class, nonFraudCheckPort);
    }

    @ParameterizedTest(name = "funded account passes for EUR {0}")
    @MethodSource("passingFundedAmounts")
    void fundedAccountPassesBelowAndExactlyAtAvailableBalance(BigDecimal amount) {
        assertEquals(
                NonFraudCheckResult.PASSED,
                nonFraudCheckPort.check(command("tok_A1B2C3D4", amount, "EUR")));
    }

    @Test
    void amountAboveAvailableBalanceReturnsInsufficientFunds() {
        assertEquals(
                NonFraudCheckResult.INSUFFICIENT_FUNDS,
                nonFraudCheckPort.check(
                        command("tok_A1B2C3D4", new BigDecimal("1000.01"), "EUR")));
    }

    @Test
    void reservedAmountReducesAvailableBalance() {
        jdbcClient.sql(
                        """
                        UPDATE "authorization".card_accounts
                        SET reserved_amount = 250.00
                        WHERE card_token = 'tok_A1B2C3D4'
                        """)
                .update();

        assertEquals(
                NonFraudCheckResult.PASSED,
                nonFraudCheckPort.check(
                        command("tok_A1B2C3D4", new BigDecimal("750.00"), "EUR")));
        assertEquals(
                NonFraudCheckResult.INSUFFICIENT_FUNDS,
                nonFraudCheckPort.check(
                        command("tok_A1B2C3D4", new BigDecimal("750.01"), "EUR")));
    }

    @Test
    void zeroBalanceSyntheticAccountReturnsInsufficientFunds() {
        assertEquals(
                NonFraudCheckResult.INSUFFICIENT_FUNDS,
                nonFraudCheckPort.check(
                        command("tok_insufficient01", new BigDecimal("0.01"), "EUR")));
    }

    @Test
    void unknownCardTokenIsRejectedBeforeAuthorization() {
        PreAuthorizationRejectionException exception = assertThrows(
                PreAuthorizationRejectionException.class,
                () -> nonFraudCheckPort.check(
                        command("tok_unknown0001", new BigDecimal("10.00"), "EUR")));

        assertEquals(Reason.UNKNOWN_CARD_TOKEN, exception.reason());
    }

    @Test
    void nonEurRequestIsRejectedBeforeAuthorization() {
        PreAuthorizationRejectionException exception = assertThrows(
                PreAuthorizationRejectionException.class,
                () -> nonFraudCheckPort.check(
                        command("tok_A1B2C3D4", new BigDecimal("10.00"), "USD")));

        assertEquals(Reason.UNSUPPORTED_CURRENCY, exception.reason());
    }

    @Test
    void accountCurrencyMismatchIsRejectedBeforeAuthorization() {
        jdbcClient.sql(
                        """
                        UPDATE "authorization".card_accounts
                        SET currency = 'USD'
                        WHERE card_token = 'tok_A1B2C3D4'
                        """)
                .update();

        PreAuthorizationRejectionException exception = assertThrows(
                PreAuthorizationRejectionException.class,
                () -> nonFraudCheckPort.check(
                        command("tok_A1B2C3D4", new BigDecimal("10.00"), "EUR")));

        assertEquals(Reason.UNSUPPORTED_CURRENCY, exception.reason());
    }

    private static Stream<Arguments> passingFundedAmounts() {
        return Stream.of(
                Arguments.of(new BigDecimal("999.99")),
                Arguments.of(new BigDecimal("1000.00")));
    }

    private static AuthorizationCommand command(
            String cardToken, BigDecimal amount, String currency) {
        return new AuthorizationCommand(
                UUID.randomUUID(),
                cardToken,
                "merchant-standard",
                "5411",
                amount,
                currency,
                "DE",
                AuthorizationChannel.ECOMMERCE,
                Instant.parse("2026-07-20T10:15:30Z"));
    }
}
