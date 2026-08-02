package io.github.monssifechadli99.transactiq.case_search

import java.math.BigDecimal
import java.time.Instant
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper

class FraudCaseSearchStore(private val client:RestClient,private val mapper:ObjectMapper,
    private val properties:CaseSearchProperties) {
    fun search(criteria:FraudCaseSearchCriteria,searchAfter:List<Any>?):SearchResult {
        val filters=buildList<Map<String,Any>> {
            term(criteria.status,"status")?.let(::add);term(criteria.fraudAssessment,"fraudAssessment")?.let(::add)
            term(criteria.assigneeId,"assigneeId")?.let(::add);term(criteria.authorizationDecision,"authorizationDecision")?.let(::add)
            term(criteria.resolutionOutcome,"resolutionOutcome")?.let(::add);term(criteria.currency,"currency")?.let(::add)
            term(criteria.country,"country")?.let(::add);term(criteria.channel,"channel")?.let(::add)
        }
        val must=criteria.query?.let { listOf(textQuery(it)) } ?: emptyList()
        val body=linkedMapOf<String,Any>(
            "size" to criteria.pageSize+1,
            "query" to mapOf("bool" to mapOf("must" to must,"filter" to filters)),
            "sort" to listOf(mapOf(criteria.sort.field to criteria.sort.direction),mapOf("caseId" to "asc")),
            "_source" to RESPONSE_FIELDS
        )
        if(searchAfter!=null)body["search_after"]=searchAfter
        try {
            val json=client.post().uri("/{alias}/_search",properties.readAlias).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(String::class.java).orEmpty()
            @Suppress("UNCHECKED_CAST") val root=mapper.readValue(json,Map::class.java) as Map<String,Any>
            @Suppress("UNCHECKED_CAST") val hits=((root["hits"] as Map<String,Any>)["hits"] as List<Map<String,Any>>)
            val page=hits.take(criteria.pageSize)
            return SearchResult(page.map(::summary),if(hits.size>criteria.pageSize)page.last()["sort"] as? List<Any> else null)
        } catch(error:RuntimeException){throw OpenSearchUnavailableException("Fraud Case search is unavailable",error)}
    }

    private fun term(value:String?,field:String)=value?.let{mapOf("term" to mapOf(field to it))}
    private fun textQuery(query:String):Map<String,Any> {
        val clauses=listOf(
            mapOf("term" to mapOf("caseId" to query)),
            mapOf("term" to mapOf("merchantId" to query)),
            mapOf("match" to mapOf("resolutionRationale" to mapOf("query" to query,"operator" to "and"))),
            mapOf("nested" to mapOf("path" to "matchedRules","query" to mapOf("match" to
                mapOf("matchedRules.evidence" to mapOf("query" to query,"operator" to "and")))))
        )
        return mapOf("bool" to mapOf("should" to clauses,"minimum_should_match" to 1))
    }
    @Suppress("UNCHECKED_CAST") private fun summary(hit:Map<String,Any>):FraudCaseSummary {
        val s=hit["_source"] as Map<String,Any>
        return FraudCaseSummary(s.string("caseId"),s.string("status"),s["assigneeId"] as String?,s.string("merchantId"),
            s.string("merchantCategoryCode"),BigDecimal(s["amount"].toString()),s.string("currency"),s.string("country"),
            s.string("channel"),s.string("authorizationDecision"),s.string("fraudAssessment"),
            (s["riskScore"] as Number).toInt(),Instant.parse(s.string("createdAt")),Instant.parse(s.string("updatedAt")),
            s["resolutionOutcome"] as String?)
    }
    private fun Map<String,Any>.string(name:String)=this[name] as String
    data class SearchResult(val items:List<FraudCaseSummary>,val lastSort:List<Any>?)
    companion object { private val RESPONSE_FIELDS=listOf("caseId","status","assigneeId","merchantId",
        "merchantCategoryCode","amount","currency","country","channel","authorizationDecision",
        "fraudAssessment","riskScore","createdAt","updatedAt","resolutionOutcome") }
}
