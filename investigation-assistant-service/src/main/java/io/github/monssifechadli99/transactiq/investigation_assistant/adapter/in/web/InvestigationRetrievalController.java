package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.web;

import io.github.monssifechadli99.transactiq.investigation_assistant.application.InvestigationRetrievalService;
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
public final class InvestigationRetrievalController {

    private static final int DEFAULT_MAX_RELATED_CASES = 5;

    private final InvestigationRetrievalService service;
    private final InvestigationApiMapper mapper;
    private final ObjectMapper objectMapper;

    public InvestigationRetrievalController(
            InvestigationRetrievalService service,
            InvestigationApiMapper mapper,
            ObjectMapper objectMapper) {
        this.service = service;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @PostMapping(
            path = "/{caseId}/investigation/retrieval",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public InvestigationRetrievalResponse.Response retrieve(
            @PathVariable UUID caseId, HttpServletRequest httpRequest) {
        InvestigationRetrievalRequest request = readAndValidate(httpRequest);
        int maxRelatedCases =
                request.maxRelatedCases() == null ? DEFAULT_MAX_RELATED_CASES : request.maxRelatedCases();
        return mapper.toResponse(service.retrieve(caseId.toString(), request.question(), maxRelatedCases));
    }

    private InvestigationRetrievalRequest readAndValidate(HttpServletRequest httpRequest) {
        try {
            InvestigationRetrievalRequest request =
                    objectMapper.readValue(httpRequest.getInputStream(), InvestigationRetrievalRequest.class);
            if (request == null) {
                throw InvalidInvestigationRequestException.malformed();
            }
            InvestigationRequestValidator.validate(request);
            return request;
        } catch (InvalidInvestigationRequestException exception) {
            throw exception;
        } catch (IOException | RuntimeException ignored) {
            // Do not retain a parser exception: it can include the malformed request content.
            throw InvalidInvestigationRequestException.malformed();
        }
    }
}
