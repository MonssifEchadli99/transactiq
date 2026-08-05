package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web.InvestigationErrorResponse.FieldError;
import java.util.List;
import org.junit.jupiter.api.Test;

class InvestigationRetrievalRequestValidationTest {

    @Test
    void questionIsTrimmedBeforeValidation() {
        InvestigationRetrievalRequest request = new InvestigationRetrievalRequest("  why is this suspicious?  ", 5);

        assertEquals("why is this suspicious?", request.question());
        assertDoesNotThrow(() -> InvestigationRequestValidator.validate(request));
    }

    @Test
    void blankQuestionIsRejected() {
        InvalidInvestigationRequestException exception = assertThrows(
                InvalidInvestigationRequestException.class,
                () -> InvestigationRequestValidator.validate(new InvestigationRetrievalRequest("   ", 5)));

        assertEquals(List.of(
                new FieldError("question", "must not be blank"),
                new FieldError("question", "size must be between 1 and 1000")), exception.fieldErrors());
    }

    @Test
    void questionOverOneThousandCharactersIsRejected() {
        String sentinel = "OVERSIZED_QUESTION_VALIDATION_SENTINEL_6A";
        String tooLong = "a".repeat(1001) + sentinel;

        InvalidInvestigationRequestException exception = assertThrows(
                InvalidInvestigationRequestException.class,
                () -> InvestigationRequestValidator.validate(new InvestigationRetrievalRequest(tooLong, 5)));

        assertEquals(List.of(new FieldError("question", "size must be between 1 and 1000")),
                exception.fieldErrors());
        assertFalse(exception.toString().contains(sentinel));
    }

    @Test
    void questionAtExactlyOneThousandCharactersIsAccepted() {
        String exact = "a".repeat(1000);

        assertDoesNotThrow(() ->
                InvestigationRequestValidator.validate(new InvestigationRetrievalRequest(exact, 5)));
    }

    @Test
    void maxRelatedCasesOutsideOneToTenIsRejected() {
        InvalidInvestigationRequestException belowMinimum = assertThrows(
                InvalidInvestigationRequestException.class,
                () -> InvestigationRequestValidator.validate(new InvestigationRetrievalRequest("why?", 0)));
        InvalidInvestigationRequestException aboveMaximum = assertThrows(
                InvalidInvestigationRequestException.class,
                () -> InvestigationRequestValidator.validate(new InvestigationRetrievalRequest("why?", 11)));

        assertEquals(List.of(new FieldError("maxRelatedCases", "must be greater than or equal to 1")),
                belowMinimum.fieldErrors());
        assertEquals(List.of(new FieldError("maxRelatedCases", "must be less than or equal to 10")),
                aboveMaximum.fieldErrors());
    }

    @Test
    void nullMaxRelatedCasesIsAccepted() {
        assertDoesNotThrow(() ->
                InvestigationRequestValidator.validate(new InvestigationRetrievalRequest("why?", null)));
    }

    @Test
    void requestStringRepresentationRedactsTheQuestion() {
        String sentinel = "ANALYST_QUESTION_LOG_SENTINEL_6A";

        String representation = new InvestigationRetrievalRequest(sentinel, 5).toString();

        assertFalse(representation.contains(sentinel));
        assertTrue(representation.contains("question=<redacted>"));
    }
}
