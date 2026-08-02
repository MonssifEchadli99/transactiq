package io.github.monssifechadli99.transactiq.case_search

import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.*
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

class ProjectionValidator {
    fun validate(key:ByteArray,event:FraudCaseProjectionEvent):FraudCaseProjectionSnapshot {
        val snapshot=if(event.hasSnapshot()) event.snapshot else invalid("snapshot is absent")
        try { UUID.fromString(event.eventId);UUID.fromString(event.caseId);UUID.fromString(snapshot.caseId);UUID.fromString(snapshot.requestId) }
        catch(error:IllegalArgumentException){invalid("identifier is not a UUID")}
        val keyCaseId=key.toString(Charsets.UTF_8)
        if(keyCaseId!=event.caseId || event.caseId!=snapshot.caseId) invalid("case identity mismatch")
        if(event.aggregateVersion<0 || event.aggregateVersion!=snapshot.aggregateVersion) invalid("aggregate version mismatch")
        if(!event.snapshotHash.matches(Regex("[0-9a-f]{64}"))) invalid("snapshot hash is malformed")
        if(hash(snapshot)!=event.snapshotHash) invalid("snapshot hash mismatch")
        val expectedStatus=when(event.eventType){
            FraudCaseProjectionEventType.CREATED->"NEW"
            FraudCaseProjectionEventType.CLAIMED->"IN_REVIEW"
            FraudCaseProjectionEventType.RESOLVED->"RESOLVED"
            else->invalid("event type is unspecified")
        }
        if(snapshot.status!=expectedStatus) invalid("event type and status are incompatible")
        if(snapshot.caseId.isBlank()||snapshot.requestId.isBlank()||snapshot.merchantId.isBlank()||
            snapshot.merchantCategoryCode.isBlank()||snapshot.amount.isBlank()||snapshot.currency.isBlank()||
            snapshot.country.isBlank()||snapshot.channel.isBlank()||snapshot.nonFraudResult.isBlank()||
            snapshot.authorizationDecision.isBlank()||snapshot.fraudAssessment.isBlank()||
            !snapshot.hasAuthorizationOccurredAt()||!snapshot.hasTransactionTime()||
            !snapshot.hasCreatedAt()||!snapshot.hasUpdatedAt()||snapshot.riskScore !in 0..100) invalid("required field is invalid")
        if(expectedStatus=="IN_REVIEW"&&!snapshot.hasAssigneeId()) invalid("claimed snapshot lacks assignee")
        if(expectedStatus=="RESOLVED"&&(!snapshot.hasAssigneeId()||!snapshot.hasResolutionOutcome()||
                    !snapshot.hasResolutionRationale()||!snapshot.hasResolvedBy()||!snapshot.hasResolvedAt()))
            invalid("resolved snapshot is incomplete")
        if(expectedStatus=="NEW"&&(snapshot.hasAssigneeId()||snapshot.hasResolutionOutcome()||snapshot.hasResolvedAt()))
            invalid("new snapshot has lifecycle metadata")
        if(expectedStatus=="IN_REVIEW"&&(snapshot.hasResolutionOutcome()||snapshot.hasResolvedAt()))
            invalid("claimed snapshot has resolution metadata")
        val allowed=setOf("PASSED","INSUFFICIENT_FUNDS","APPROVED","DECLINED","CLEAR","REVIEW","HIGH_RISK")
        if(snapshot.nonFraudResult !in allowed||snapshot.authorizationDecision !in allowed||snapshot.fraudAssessment !in allowed)
            invalid("business enum is invalid")
        snapshot.matchedRulesList.forEach { if(it.ruleCode.isBlank()||it.evidence.isBlank()||it.severity !in setOf("REVIEW","HIGH_RISK")) invalid("rule evidence is invalid") }
        return snapshot
    }
    fun hash(snapshot:FraudCaseProjectionSnapshot)=HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(snapshot.toByteArray()))
    private fun invalid(message:String):Nothing=throw InvalidProjectionException(message)
}
