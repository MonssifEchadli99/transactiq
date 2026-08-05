package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web;

import io.github.monssifechadli99.transactiq.investigation_assistant.application.InvestigationAnswerService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/fraud-cases")
public final class InvestigationAnswerController {

    private final InvestigationAnswerService service;
    private final InvestigationAnswerApiMapper mapper;
    private final ObjectMapper objectMapper;

    public InvestigationAnswerController(
            InvestigationAnswerService service,
            InvestigationAnswerApiMapper mapper,
            ObjectMapper objectMapper) {
        this.service = service;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @PostMapping(
            path = "/{caseId}/investigation/answer",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public InvestigationAnswerResponse.Response answer(
            @PathVariable UUID caseId, HttpServletRequest httpRequest) {
        InvestigationAnswerRequest request = readAndValidate(httpRequest);
        return mapper.toResponse(service.answer(caseId.toString(), request.question()));
    }

    private InvestigationAnswerRequest readAndValidate(HttpServletRequest httpRequest) {
        try {
            InvestigationAnswerRequest request =
                    objectMapper.readValue(httpRequest.getInputStream(), InvestigationAnswerRequest.class);
            if (request == null) {
                throw InvalidInvestigationRequestException.malformed();
            }
            InvestigationAnswerRequestValidator.validate(request);
            return request;
        } catch (InvalidInvestigationRequestException exception) {
            throw exception;
        } catch (IOException | RuntimeException ignored) {
            // Do not retain a parser exception: it can include malformed analyst input.
            throw InvalidInvestigationRequestException.malformed();
        }
    }
}
