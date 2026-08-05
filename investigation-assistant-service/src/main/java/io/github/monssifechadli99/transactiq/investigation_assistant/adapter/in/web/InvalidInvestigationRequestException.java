package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web;

import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web.InvestigationErrorResponse.FieldError;
import java.util.List;

final class InvalidInvestigationRequestException extends RuntimeException {

    private static final String ERROR_CODE = "INVALID_INVESTIGATION_REQUEST";
    private static final String SAFE_MESSAGE = "Invalid investigation request";

    private final List<FieldError> fieldErrors;

    private InvalidInvestigationRequestException(List<FieldError> fieldErrors) {
        super(SAFE_MESSAGE);
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    static InvalidInvestigationRequestException malformed() {
        return new InvalidInvestigationRequestException(List.of());
    }

    static InvalidInvestigationRequestException validation(List<FieldError> fieldErrors) {
        if (fieldErrors.isEmpty()) {
            throw new IllegalArgumentException("Validation errors must not be empty");
        }
        return new InvalidInvestigationRequestException(fieldErrors);
    }

    String code() {
        return ERROR_CODE;
    }

    List<FieldError> fieldErrors() {
        return fieldErrors;
    }
}
