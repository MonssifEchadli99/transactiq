package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web;

import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web.InvestigationErrorResponse.FieldError;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class InvestigationAnswerRequestValidator {

    private static final int MAX_QUESTION_LENGTH = 1000;

    private InvestigationAnswerRequestValidator() {}

    static void validate(InvestigationAnswerRequest request) {
        List<FieldError> errors = new ArrayList<>();
        String question = request.question();
        if (question == null || question.isBlank()) {
            errors.add(new FieldError("question", "must not be blank"));
        }
        if (question != null && (question.isEmpty() || question.length() > MAX_QUESTION_LENGTH)) {
            errors.add(new FieldError("question", "size must be between 1 and 1000"));
        }
        if (!errors.isEmpty()) {
            errors.sort(Comparator.comparing(FieldError::field).thenComparing(FieldError::message));
            throw InvalidInvestigationRequestException.validation(errors);
        }
    }
}
