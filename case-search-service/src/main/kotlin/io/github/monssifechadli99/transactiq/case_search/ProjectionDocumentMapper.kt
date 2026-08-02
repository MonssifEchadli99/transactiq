package io.github.monssifechadli99.transactiq.case_search

import com.google.protobuf.Timestamp
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.*
import java.time.Instant

class ProjectionDocumentMapper {
    fun map(event:FraudCaseProjectionEvent,s:FraudCaseProjectionSnapshot):Map<String,Any> = buildMap {
        put("caseId",s.caseId);put("requestId",s.requestId);put("status",s.status)
        if(s.hasAssigneeId())put("assigneeId",s.assigneeId)
        put("aggregateVersion",s.aggregateVersion);put("snapshotHash",event.snapshotHash)
        put("authorizationOccurredAt",instant(s.authorizationOccurredAt));put("merchantId",s.merchantId)
        put("merchantCategoryCode",s.merchantCategoryCode);put("amount",s.amount);put("currency",s.currency)
        put("country",s.country);put("channel",s.channel);put("transactionTime",instant(s.transactionTime))
        put("nonFraudResult",s.nonFraudResult);put("authorizationDecision",s.authorizationDecision)
        if(s.hasDeclineReason())put("declineReason",s.declineReason)
        put("fraudAssessment",s.fraudAssessment);put("riskScore",s.riskScore);put("caseRequired",s.caseRequired)
        put("createdAt",instant(s.createdAt));put("updatedAt",instant(s.updatedAt))
        put("matchedRules",s.matchedRulesList.map{mapOf("ruleCode" to it.ruleCode,"severity" to it.severity,
            "evidence" to it.evidence,"scoreContribution" to it.scoreContribution)})
        if(s.hasResolutionOutcome())put("resolutionOutcome",s.resolutionOutcome)
        if(s.hasResolutionRationale())put("resolutionRationale",s.resolutionRationale)
        if(s.hasResolvedBy())put("resolvedBy",s.resolvedBy)
        if(s.hasResolvedAt())put("resolvedAt",instant(s.resolvedAt))
    }
    private fun instant(value:Timestamp)=Instant.ofEpochSecond(value.seconds,value.nanos.toLong()).toString()
}
