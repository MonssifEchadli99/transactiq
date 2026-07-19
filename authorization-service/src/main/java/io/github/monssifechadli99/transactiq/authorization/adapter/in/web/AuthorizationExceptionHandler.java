package io.github.monssifechadli99.transactiq.authorization.adapter.in.web;

import io.github.monssifechadli99.transactiq.authorization.api.AuthorizationErrorResponse;
import io.github.monssifechadli99.transactiq.authorization.api.AuthorizationErrorResponse.CodeOnly;
import io.github.monssifechadli99.transactiq.authorization.api.AuthorizationErrorResponse.ErrorCode;
import io.github.monssifechadli99.transactiq.authorization.api.AuthorizationErrorResponse.Validation;
import io.github.monssifechadli99.transactiq.authorization.api.AuthorizationErrorResponse.ValidationFieldError;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthorizationExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<AuthorizationErrorResponse> handleValidationFailure(
            MethodArgumentNotValidException exception) {
        List<ValidationFieldError> fieldErrors = exception.getBindingResult().getAllErrors().stream()
                .map(AuthorizationExceptionHandler::toValidationFieldError)
                .sorted(Comparator.comparing(ValidationFieldError::field)
                        .thenComparing(ValidationFieldError::message))
                .toList();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new Validation(ErrorCode.INVALID_AUTHORIZATION_REQUEST, fieldErrors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<AuthorizationErrorResponse> handleMalformedRequest() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new CodeOnly(ErrorCode.MALFORMED_AUTHORIZATION_REQUEST));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<AuthorizationErrorResponse> handleUnexpectedFailure() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new CodeOnly(ErrorCode.AUTHORIZATION_PROCESSING_ERROR));
    }

    private static ValidationFieldError toValidationFieldError(ObjectError error) {
        String field = error instanceof FieldError fieldError
                ? fieldError.getField()
                : error.getObjectName();
        String message = Objects.requireNonNullElse(error.getDefaultMessage(), "invalid value");
        return new ValidationFieldError(field, message);
    }
}
