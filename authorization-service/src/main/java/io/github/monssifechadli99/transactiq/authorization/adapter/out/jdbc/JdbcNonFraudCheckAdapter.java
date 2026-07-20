package io.github.monssifechadli99.transactiq.authorization.adapter.out.jdbc;

import static io.github.monssifechadli99.transactiq.authorization.application.model.PreAuthorizationRejectionException.Reason.UNKNOWN_CARD_TOKEN;
import static io.github.monssifechadli99.transactiq.authorization.application.model.PreAuthorizationRejectionException.Reason.UNSUPPORTED_CURRENCY;

import io.github.monssifechadli99.transactiq.authorization.application.model.PreAuthorizationRejectionException;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.NonFraudCheckPort;
import io.github.monssifechadli99.transactiq.authorization.domain.NonFraudCheckResult;
import java.math.BigDecimal;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcNonFraudCheckAdapter implements NonFraudCheckPort {

    private static final String SUPPORTED_CURRENCY = "EUR";

    private final JdbcClient jdbcClient;

    public JdbcNonFraudCheckAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public NonFraudCheckResult check(AuthorizationCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        if (!SUPPORTED_CURRENCY.equals(command.currency())) {
            throw new PreAuthorizationRejectionException(UNSUPPORTED_CURRENCY);
        }

        CardAccountBalance account = jdbcClient.sql(
                        """
                        SELECT currency, posted_balance, reserved_amount
                        FROM "authorization".card_accounts
                        WHERE card_token = :cardToken
                        FOR UPDATE
                        """)
                .param("cardToken", command.cardToken())
                .query((resultSet, rowNumber) -> new CardAccountBalance(
                        resultSet.getString("currency"),
                        resultSet.getBigDecimal("posted_balance"),
                        resultSet.getBigDecimal("reserved_amount")))
                .optional()
                .orElseThrow(() -> new PreAuthorizationRejectionException(UNKNOWN_CARD_TOKEN));

        if (!command.currency().equals(account.currency())) {
            throw new PreAuthorizationRejectionException(UNSUPPORTED_CURRENCY);
        }

        return command.amount().compareTo(account.availableBalance()) <= 0
                ? NonFraudCheckResult.PASSED
                : NonFraudCheckResult.INSUFFICIENT_FUNDS;
    }

    private record CardAccountBalance(
            String currency, BigDecimal postedBalance, BigDecimal reservedAmount) {

        private BigDecimal availableBalance() {
            return postedBalance.subtract(reservedAmount);
        }
    }
}
