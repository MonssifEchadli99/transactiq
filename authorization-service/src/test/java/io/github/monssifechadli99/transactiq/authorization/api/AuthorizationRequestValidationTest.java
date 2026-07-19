package io.github.monssifechadli99.transactiq.authorization.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationChannel;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class AuthorizationRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    void acceptsFullyValidRequest() {
        Set<ConstraintViolation<AuthorizationRequest>> violations = validator.validate(validRequest());

        assertTrue(violations.isEmpty());
    }

    @ParameterizedTest(name = "{0} is required")
    @ValueSource(strings = {
        "requestId",
        "cardToken",
        "merchantId",
        "merchantCategoryCode",
        "amount",
        "currency",
        "country",
        "channel",
        "transactionTime"
    })
    void rejectsMissingRequiredField(String field) {
        assertViolationFor(field, requestWith(field, null));
    }

    @ParameterizedTest(name = "{0} rejects missing value: [{1}]")
    @MethodSource("emptyOrWhitespaceRequiredFields")
    void rejectsEmptyOrWhitespaceRequiredField(String field, String value) {
        assertViolationFor(field, requestWith(field, value));
    }

    @ParameterizedTest(name = "rejects card token: {0}")
    @NullSource
    @ValueSource(strings = {
        "card_A1B2C3D4",
        "tok_1234567",
        "tok_1234567!",
        "tok_1234567890123456789012345678901234567890123456789012345678901"
    })
    void rejectsInvalidCardToken(String cardToken) {
        assertViolationFor("cardToken", requestWith("cardToken", cardToken));
    }

    @ParameterizedTest(name = "rejects merchant ID: [{0}]")
    @ValueSource(strings = {
        "",
        "   ",
        "merchant-12345678901234567890123456789012345678901234567890123456"
    })
    void rejectsInvalidMerchantId(String merchantId) {
        assertViolationFor("merchantId", requestWith("merchantId", merchantId));
    }

    @ParameterizedTest(name = "rejects MCC: {0}")
    @ValueSource(strings = {"541", "54111", "54A1"})
    void rejectsInvalidMerchantCategoryCode(String merchantCategoryCode) {
        assertViolationFor(
                "merchantCategoryCode",
                requestWith("merchantCategoryCode", merchantCategoryCode));
    }

    @ParameterizedTest(name = "rejects amount: {0}")
    @MethodSource("invalidAmounts")
    void rejectsInvalidAmount(BigDecimal amount) {
        assertViolationFor("amount", requestWith("amount", amount));
    }

    @ParameterizedTest(name = "rejects currency: {0}")
    @ValueSource(strings = {"eur", "EU", "EURO", "E1R"})
    void rejectsInvalidCurrency(String currency) {
        assertViolationFor("currency", requestWith("currency", currency));
    }

    @ParameterizedTest(name = "rejects country: {0}")
    @ValueSource(strings = {"de", "D", "DEU", "D1"})
    void rejectsInvalidCountry(String country) {
        assertViolationFor("country", requestWith("country", country));
    }

    private static Stream<Arguments> invalidAmounts() {
        return Stream.of(
                Arguments.of(BigDecimal.ZERO),
                Arguments.of(new BigDecimal("-0.01")),
                Arguments.of(new BigDecimal("1.001")),
                Arguments.of(new BigDecimal("1000000000000.00")));
    }

    private static Stream<Arguments> emptyOrWhitespaceRequiredFields() {
        return Stream.of(
                Arguments.of("merchantCategoryCode", ""),
                Arguments.of("merchantCategoryCode", "   "),
                Arguments.of("currency", ""),
                Arguments.of("currency", "   "),
                Arguments.of("country", ""),
                Arguments.of("country", "   "));
    }

    private static AuthorizationRequest validRequest() {
        return new AuthorizationRequest(
                UUID.fromString("d5e75b60-a263-4f76-b5d0-a35f1a09bc67"),
                "tok_A1B2C3D4",
                "merchant-123",
                "5411",
                new BigDecimal("42.50"),
                "EUR",
                "DE",
                AuthorizationChannel.ECOMMERCE,
                Instant.parse("2026-07-19T10:15:30Z"));
    }

    private static AuthorizationRequest requestWith(String field, Object value) {
        AuthorizationRequest valid = validRequest();

        return new AuthorizationRequest(
                field.equals("requestId") ? (UUID) value : valid.requestId(),
                field.equals("cardToken") ? (String) value : valid.cardToken(),
                field.equals("merchantId") ? (String) value : valid.merchantId(),
                field.equals("merchantCategoryCode")
                        ? (String) value
                        : valid.merchantCategoryCode(),
                field.equals("amount") ? (BigDecimal) value : valid.amount(),
                field.equals("currency") ? (String) value : valid.currency(),
                field.equals("country") ? (String) value : valid.country(),
                field.equals("channel") ? (AuthorizationChannel) value : valid.channel(),
                field.equals("transactionTime") ? (Instant) value : valid.transactionTime());
    }

    private static void assertViolationFor(String field, AuthorizationRequest request) {
        Set<ConstraintViolation<AuthorizationRequest>> violations = validator.validate(request);

        assertTrue(
                violations.stream()
                        .map(violation -> violation.getPropertyPath().toString())
                        .anyMatch(field::equals),
                () -> "Expected a validation violation for " + field + " but got " + violations);
    }
}
