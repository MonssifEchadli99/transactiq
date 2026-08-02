package io.github.monssifechadli99.transactiq.case_search

import com.google.protobuf.Timestamp
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.*
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProjectionValidatorTest {
    private val validator=ProjectionValidator()
    private val caseId="00000000-0000-0000-0000-000000000701"
    @Test fun `invalid identity type status version and hash are rejected`(){
        val snapshot=validSnapshot()
        val event=FraudCaseProjectionEvent.newBuilder().setEventId("00000000-0000-0000-0000-000000000702").setEventType(FraudCaseProjectionEventType.CREATED)
            .setCaseId(caseId).setAggregateVersion(0).setOccurredAt(time()).setSnapshot(snapshot)
            .setSnapshotHash(validator.hash(snapshot)).build()
        assertThrows(InvalidProjectionException::class.java){validator.validate("00000000-0000-0000-0000-000000000799".toByteArray(),event)}
        assertThrows(InvalidProjectionException::class.java){validator.validate(caseId.toByteArray(),event.toBuilder().setAggregateVersion(1).build())}
        assertThrows(InvalidProjectionException::class.java){validator.validate(caseId.toByteArray(),event.toBuilder().setEventType(FraudCaseProjectionEventType.RESOLVED).build())}
        assertThrows(InvalidProjectionException::class.java){validator.validate(caseId.toByteArray(),event.toBuilder().setSnapshotHash("0".repeat(64)).build())}
    }
    private fun validSnapshot()=FraudCaseProjectionSnapshot.newBuilder().setCaseId(caseId).setRequestId("00000000-0000-0000-0000-000000000703")
        .setStatus("NEW").setAggregateVersion(0).setAuthorizationOccurredAt(time()).setMerchantId("merchant")
        .setMerchantCategoryCode("7995").setAmount("125").setCurrency("EUR").setCountry("DE")
        .setChannel("ECOMMERCE").setTransactionTime(time()).setNonFraudResult("PASSED")
        .setAuthorizationDecision("APPROVED").setFraudAssessment("REVIEW").setRiskScore(25).setCaseRequired(true)
        .setCreatedAt(time()).setUpdatedAt(time()).build()
    private fun time()=Timestamp.newBuilder().setSeconds(1).build()
}
