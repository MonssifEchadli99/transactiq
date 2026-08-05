package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web;

import java.util.List;

public sealed interface InvestigationErrorResponse
        permits InvestigationErrorResponse.CodeOnly, InvestigationErrorResponse.Validation {
    String code();

    record CodeOnly(String code) implements InvestigationErrorResponse {}

    record Validation(String code, List<FieldError> fieldErrors) implements InvestigationErrorResponse {
        public Validation {
            fieldErrors = List.copyOf(fieldErrors);
        }
    }

    record FieldError(String field, String message) {}
}
