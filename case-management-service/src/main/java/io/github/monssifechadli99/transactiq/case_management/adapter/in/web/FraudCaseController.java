package io.github.monssifechadli99.transactiq.case_management.adapter.in.web;

import io.github.monssifechadli99.transactiq.case_management.api.FraudCaseApiResponse;
import io.github.monssifechadli99.transactiq.case_management.api.FraudCaseClaimRequest;
import io.github.monssifechadli99.transactiq.case_management.api.FraudCaseResolveRequest;
import io.github.monssifechadli99.transactiq.case_management.application.service.FraudCaseLifecycleService;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseAssignmentFilter;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseStatus;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fraud-cases")
public final class FraudCaseController {
    public static final String ANALYST_HEADER = "X-Analyst-Id";
    private final FraudCaseLifecycleService service;
    private final FraudCaseApiMapper mapper;

    public FraudCaseController(FraudCaseLifecycleService service, FraudCaseApiMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public FraudCaseApiResponse.Page list(
            @RequestParam(required = false) FraudCaseStatus status,
            @RequestParam(required = false) FraudCaseAssignmentFilter assignment,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String cursor,
            @RequestHeader(name = ANALYST_HEADER, required = false) String analystId) {
        return mapper.toPage(service.list(status, assignment, analystId, pageSize, cursor));
    }

    @GetMapping(path = "/{caseId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public FraudCaseApiResponse.Detail details(@PathVariable UUID caseId) {
        return mapper.toDetail(service.details(caseId));
    }

    @PostMapping(
            path = "/{caseId}/claim",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public FraudCaseApiResponse.Detail claim(
            @PathVariable UUID caseId,
            @RequestHeader(name = ANALYST_HEADER, required = false) String analystId,
            @Valid @RequestBody FraudCaseClaimRequest request) {
        return mapper.toDetail(service.claim(caseId, analystId, request.expectedVersion()));
    }

    @PostMapping(
            path = "/{caseId}/resolve",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public FraudCaseApiResponse.Detail resolve(
            @PathVariable UUID caseId,
            @RequestHeader(name = ANALYST_HEADER, required = false) String analystId,
            @Valid @RequestBody FraudCaseResolveRequest request) {
        return mapper.toDetail(service.resolve(
                caseId, analystId, request.expectedVersion(),
                request.outcome(), request.rationale()));
    }

    @GetMapping(path = "/{caseId}/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public FraudCaseApiResponse.History history(@PathVariable UUID caseId) {
        return mapper.toHistory(service.history(caseId));
    }
}
