package io.github.monssifechadli99.transactiq.authorization.adapter.in.web;

import io.github.monssifechadli99.transactiq.authorization.api.AuthorizationErrorResponse.CodeOnly;
import io.github.monssifechadli99.transactiq.authorization.api.AuthorizationErrorResponse.ErrorCode;
import io.github.monssifechadli99.transactiq.authorization.api.AuthorizationRequest;
import io.github.monssifechadli99.transactiq.authorization.application.model.AuthorizationProcessingResult;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizeTransactionUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/authorizations")
public class AuthorizationController {

    private final AuthorizeTransactionUseCase authorizeTransactionUseCase;
    private final AuthorizationHttpMapper mapper;

    public AuthorizationController(
            AuthorizeTransactionUseCase authorizeTransactionUseCase,
            AuthorizationHttpMapper mapper) {
        this.authorizeTransactionUseCase = authorizeTransactionUseCase;
        this.mapper = mapper;
    }

    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> authorize(
            @Valid @RequestBody AuthorizationRequest request) {
        AuthorizationCommand command = mapper.toCommand(request);
        AuthorizationProcessingResult result = authorizeTransactionUseCase.authorize(command);

        return switch (result) {
            case AuthorizationProcessingResult.Completed completed ->
                    ResponseEntity.ok(mapper.toResponse(command, completed.outcome()));
            case AuthorizationProcessingResult.Pending pending ->
                    ResponseEntity.status(HttpStatus.ACCEPTED)
                            .body(mapper.toPendingResponse(pending.requestId()));
            case AuthorizationProcessingResult.Conflict conflict ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(new CodeOnly(ErrorCode.REQUEST_ID_CONFLICT));
        };
    }
}
