package io.github.monssifechadli99.transactiq.case_management.api;

import java.util.List;

public sealed interface FraudCaseErrorResponse
        permits FraudCaseErrorResponse.CodeOnly, FraudCaseErrorResponse.Validation {
    String code();

    record CodeOnly(String code) implements FraudCaseErrorResponse {}

    record Validation(String code, List<FieldError> fieldErrors) implements FraudCaseErrorResponse {
        public Validation { fieldErrors = List.copyOf(fieldErrors); }
    }

    record FieldError(String field, String message) {}
}
