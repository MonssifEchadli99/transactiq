package io.github.monssifechadli99.transactiq.case_search

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/fraud-cases")
class FraudCaseSearchController(private val store:FraudCaseSearchStore,private val cursors:SearchCursorCodec,
    private val properties:CaseSearchProperties) {
    @GetMapping("/search") fun search(
        @RequestParam(required=false,name="q") rawQuery:String?,
        @RequestParam(required=false) status:String?,@RequestParam(required=false) fraudAssessment:String?,
        @RequestParam(required=false) assigneeId:String?,@RequestParam(required=false) authorizationDecision:String?,
        @RequestParam(required=false) resolutionOutcome:String?,@RequestParam(required=false) currency:String?,
        @RequestParam(required=false) country:String?,@RequestParam(required=false) channel:String?,
        @RequestParam(defaultValue="CREATED_AT_DESC") sort:FraudCaseSearchSort,
        @RequestParam(required=false) pageSize:Int?,@RequestParam(required=false) cursor:String?
    ):FraudCaseSearchResponse {
        val query=rawQuery?.trim()?.takeIf{it.isNotEmpty()}
        if(rawQuery!=null&&query==null)invalid("INVALID_QUERY","q must not be blank")
        if(query!=null&&query.length>200)invalid("INVALID_QUERY","q must contain at most 200 characters")
        enum(status,"status",STATUSES);enum(fraudAssessment,"fraudAssessment",ASSESSMENTS)
        enum(authorizationDecision,"authorizationDecision",DECISIONS);enum(resolutionOutcome,"resolutionOutcome",OUTCOMES)
        format(currency,"currency",Regex("[A-Z]{3}"));format(country,"country",Regex("[A-Z]{2}"));enum(channel,"channel",CHANNELS)
        assigneeId?.let{if(it.isBlank()||it.length>100)invalid("INVALID_FILTER","Unsupported assigneeId")}
        val size=pageSize?:properties.defaultPageSize
        if(size !in 1..properties.maximumPageSize)invalid("INVALID_PAGE_SIZE","pageSize must be between 1 and ${properties.maximumPageSize}")
        val criteria=FraudCaseSearchCriteria(query,status,fraudAssessment,assigneeId?.trim()?.takeIf(String::isNotEmpty),
            authorizationDecision,resolutionOutcome,currency,country,channel,sort,size,cursor)
        val result=store.search(criteria,cursor?.let{cursors.decode(it,sort)})
        val next=if(result.items.size==size&&result.lastSort!=null)cursors.encode(sort,result.lastSort) else null
        return FraudCaseSearchResponse(result.items,next)
    }
    private fun enum(value:String?,name:String,allowed:Set<String>){if(value!=null&&value !in allowed)invalid("INVALID_FILTER","Unsupported $name")}
    private fun format(value:String?,name:String,pattern:Regex){if(value!=null&&!pattern.matches(value))invalid("INVALID_FILTER","Unsupported $name")}
    private fun invalid(code:String,message:String):Nothing=throw InvalidSearchRequestException(code,message)
    companion object {
        private val STATUSES=setOf("NEW","IN_REVIEW","RESOLVED");private val ASSESSMENTS=setOf("REVIEW","HIGH_RISK")
        private val DECISIONS=setOf("APPROVED","DECLINED");private val OUTCOMES=setOf("CONFIRMED_FRAUD","FALSE_POSITIVE")
        private val CHANNELS=setOf("ECOMMERCE","POINT_OF_SALE")
    }
}
