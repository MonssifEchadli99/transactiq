package io.github.monssifechadli99.transactiq.authorization.api;

import java.util.List;

public sealed interface AuthorizationErrorResponse
        permits AuthorizationErrorResponse.Validation, AuthorizationErrorResponse.CodeOnly {

    ErrorCode code();

    enum ErrorCode {
        INVALID_AUTHORIZATION_REQUEST,
        MALFORMED_AUTHORIZATION_REQUEST,
        UNKNOWN_CARD_TOKEN,
        UNSUPPORTED_CURRENCY,
        REQUEST_ID_CONFLICT,
        AUTHORIZATION_PROCESSING_ERROR
    }

    record Validation(
            ErrorCode code,
            List<ValidationFieldError> fieldErrors) implements AuthorizationErrorResponse {

        public Validation {
            fieldErrors = List.copyOf(fieldErrors);
        }
    }

    record CodeOnly(ErrorCode code) implements AuthorizationErrorResponse {
    }

    record ValidationFieldError(
            String field,
            String message) {
    }
}
