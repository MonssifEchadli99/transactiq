package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web;

import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web.InvestigationErrorResponse.CodeOnly;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web.InvestigationErrorResponse.FieldError;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web.InvestigationErrorResponse.Validation;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EmbeddingProviderUnavailableException;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EvidenceStoreUnavailableException;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.FocalEvidenceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = InvestigationRetrievalController.class)
public final class InvestigationExceptionHandler {

    @ExceptionHandler(FocalEvidenceNotFoundException.class)
    ResponseEntity<InvestigationErrorResponse> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new CodeOnly("FOCAL_EVIDENCE_NOT_FOUND"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<InvestigationErrorResponse> invalidRequest() {
        return ResponseEntity.badRequest().body(new CodeOnly("INVALID_INVESTIGATION_REQUEST"));
    }

    @ExceptionHandler(InvalidInvestigationRequestException.class)
    ResponseEntity<InvestigationErrorResponse> validation(InvalidInvestigationRequestException exception) {
        if (exception.fieldErrors().isEmpty()) {
            return ResponseEntity.badRequest().body(new CodeOnly(exception.code()));
        }
        return ResponseEntity.badRequest().body(new Validation(exception.code(), exception.fieldErrors()));
    }

    @ExceptionHandler({EmbeddingProviderUnavailableException.class, EvidenceStoreUnavailableException.class})
    ResponseEntity<InvestigationErrorResponse> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new CodeOnly("INVESTIGATION_RETRIEVAL_UNAVAILABLE"));
    }
}
