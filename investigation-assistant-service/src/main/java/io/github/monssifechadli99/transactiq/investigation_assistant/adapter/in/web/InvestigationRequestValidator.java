package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web;

import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web.InvestigationErrorResponse.FieldError;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class InvestigationRequestValidator {

    private static final int MAX_QUESTION_LENGTH = 1000;
    private static final int MIN_RELATED_CASES = 1;
    private static final int MAX_RELATED_CASES = 10;

    private InvestigationRequestValidator() {}

    static void validate(InvestigationRetrievalRequest request) {
        List<FieldError> errors = new ArrayList<>();
        String question = request.question();
        if (question == null || question.isBlank()) {
            errors.add(new FieldError("question", "must not be blank"));
        }
        if (question != null && (question.isEmpty() || question.length() > MAX_QUESTION_LENGTH)) {
            errors.add(new FieldError("question", "size must be between 1 and 1000"));
        }

        Integer maxRelatedCases = request.maxRelatedCases();
        if (maxRelatedCases != null && maxRelatedCases < MIN_RELATED_CASES) {
            errors.add(new FieldError("maxRelatedCases", "must be greater than or equal to 1"));
        }
        if (maxRelatedCases != null && maxRelatedCases > MAX_RELATED_CASES) {
            errors.add(new FieldError("maxRelatedCases", "must be less than or equal to 10"));
        }

        if (!errors.isEmpty()) {
            errors.sort(Comparator.comparing(FieldError::field).thenComparing(FieldError::message));
            throw InvalidInvestigationRequestException.validation(errors);
        }
    }
}
