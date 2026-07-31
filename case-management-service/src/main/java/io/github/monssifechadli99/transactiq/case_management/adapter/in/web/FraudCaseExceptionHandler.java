package io.github.monssifechadli99.transactiq.case_management.adapter.in.web;

import io.github.monssifechadli99.transactiq.case_management.api.FraudCaseErrorResponse;
import io.github.monssifechadli99.transactiq.case_management.api.FraudCaseErrorResponse.CodeOnly;
import io.github.monssifechadli99.transactiq.case_management.api.FraudCaseErrorResponse.FieldError;
import io.github.monssifechadli99.transactiq.case_management.api.FraudCaseErrorResponse.Validation;
import io.github.monssifechadli99.transactiq.case_management.application.service.FraudCaseConflictException;
import io.github.monssifechadli99.transactiq.case_management.application.service.FraudCaseNotFoundException;
import io.github.monssifechadli99.transactiq.case_management.application.service.InvalidFraudCaseRequestException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = FraudCaseController.class)
public final class FraudCaseExceptionHandler {
    @ExceptionHandler(FraudCaseNotFoundException.class)
    ResponseEntity<FraudCaseErrorResponse> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new CodeOnly("FRAUD_CASE_NOT_FOUND"));
    }

    @ExceptionHandler(FraudCaseConflictException.class)
    ResponseEntity<FraudCaseErrorResponse> conflict(FraudCaseConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new CodeOnly(exception.code()));
    }

    @ExceptionHandler(InvalidFraudCaseRequestException.class)
    ResponseEntity<FraudCaseErrorResponse> invalidRequest(InvalidFraudCaseRequestException exception) {
        return ResponseEntity.badRequest().body(new CodeOnly(exception.code()));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingRequestHeaderException.class})
    ResponseEntity<FraudCaseErrorResponse> invalidParameter() {
        return ResponseEntity.badRequest().body(new CodeOnly("INVALID_FRAUD_CASE_REQUEST"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<FraudCaseErrorResponse> malformedBody() {
        return ResponseEntity.badRequest().body(new CodeOnly("MALFORMED_FRAUD_CASE_REQUEST"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<FraudCaseErrorResponse> validation(MethodArgumentNotValidException exception) {
        List<FieldError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(),
                        Objects.requireNonNullElse(error.getDefaultMessage(), "invalid value")))
                .sorted(Comparator.comparing(FieldError::field).thenComparing(FieldError::message))
                .toList();
        return ResponseEntity.badRequest()
                .body(new Validation("INVALID_FRAUD_CASE_REQUEST", errors));
    }
}
