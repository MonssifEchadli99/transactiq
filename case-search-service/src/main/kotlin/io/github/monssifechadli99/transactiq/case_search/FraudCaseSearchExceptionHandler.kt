package io.github.monssifechadli99.transactiq.case_search

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice(assignableTypes=[FraudCaseSearchController::class])
class FraudCaseSearchExceptionHandler {
    data class ErrorResponse(val code:String,val message:String)
    @ExceptionHandler(InvalidSearchRequestException::class)
    fun invalid(error:InvalidSearchRequestException)=ResponseEntity.badRequest().body(ErrorResponse(error.code,error.message.orEmpty()))
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun invalidParameter(error:MethodArgumentTypeMismatchException)=ResponseEntity.badRequest()
        .body(ErrorResponse("INVALID_QUERY","Unsupported ${error.name}"))
    @ExceptionHandler(OpenSearchUnavailableException::class)
    fun unavailable(error:OpenSearchUnavailableException)=ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(ErrorResponse("SEARCH_UNAVAILABLE","Fraud Case search is temporarily unavailable"))
}
