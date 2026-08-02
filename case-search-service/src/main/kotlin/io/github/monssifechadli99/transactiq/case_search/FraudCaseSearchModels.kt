package io.github.monssifechadli99.transactiq.case_search

import java.math.BigDecimal
import java.time.Instant

enum class FraudCaseSearchSort(val field:String,val direction:String) {
    CREATED_AT_ASC("createdAt","asc"), CREATED_AT_DESC("createdAt","desc"),
    UPDATED_AT_ASC("updatedAt","asc"), UPDATED_AT_DESC("updatedAt","desc")
}

data class FraudCaseSearchCriteria(
    val query:String?, val status:String?, val fraudAssessment:String?, val assigneeId:String?,
    val authorizationDecision:String?, val resolutionOutcome:String?, val currency:String?,
    val country:String?, val channel:String?, val sort:FraudCaseSearchSort,
    val pageSize:Int, val cursor:String?
)

data class FraudCaseSummary(
    val caseId:String, val status:String, val assigneeId:String?, val merchantId:String,
    val merchantCategoryCode:String, val amount:BigDecimal, val currency:String, val country:String,
    val channel:String, val authorizationDecision:String, val fraudAssessment:String,
    val riskScore:Int, val createdAt:Instant, val updatedAt:Instant, val resolutionOutcome:String?
)

data class FraudCaseSearchResponse(val items:List<FraudCaseSummary>,val nextCursor:String?)

class InvalidSearchRequestException(val code:String,message:String):IllegalArgumentException(message)
