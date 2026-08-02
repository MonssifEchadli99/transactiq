package io.github.monssifechadli99.transactiq.case_management.projection;

import static org.junit.jupiter.api.Assertions.*;
import io.github.monssifechadli99.transactiq.case_management.domain.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FraudCaseProjectionMapperTest {
    private final FraudCaseProjectionMapper mapper=new FraudCaseProjectionMapper();
    @Test void identicalSnapshotsHaveIdenticalHashesAndMaterialChangesDoNot(){
        var first=mapper.map(aCase(0,"NEW"),FraudCaseProjectionType.CREATED,UUID.randomUUID());
        var duplicate=mapper.map(aCase(0,"NEW"),FraudCaseProjectionType.CREATED,UUID.randomUUID());
        var changed=mapper.map(aCase(1,"IN_REVIEW"),FraudCaseProjectionType.CLAIMED,UUID.randomUUID());
        assertEquals(first.getSnapshotHash(),duplicate.getSnapshotHash());
        assertNotEquals(first.getSnapshotHash(),changed.getSnapshotHash());
        assertFalse(first.getSnapshot().hasAssigneeId());
        assertFalse(first.getSnapshot().toString().contains("fingerprint"));
    }
    @Test void duplicateRuleCodesUseAllFieldsAsCanonicalTieBreakers(){
        var firstRules=List.of(
                new FraudRuleSnapshot("SAME_CODE",FraudRuleSeverity.REVIEW,"z evidence",10),
                new FraudRuleSnapshot("SAME_CODE",FraudRuleSeverity.HIGH_RISK,"a evidence",70));
        var reversed=List.of(firstRules.getLast(),firstRules.getFirst());
        var first=mapper.map(aCase(0,"NEW",firstRules),FraudCaseProjectionType.CREATED,UUID.randomUUID());
        var second=mapper.map(aCase(0,"NEW",reversed),FraudCaseProjectionType.CREATED,UUID.randomUUID());
        assertEquals(first.getSnapshotHash(),second.getSnapshotHash());
        assertEquals(first.getSnapshot(),second.getSnapshot());
    }
    private static FraudCase aCase(long version,String status){
        return aCase(version,status,List.of(new FraudRuleSnapshot(
                "RISKY_MCC",FraudRuleSeverity.REVIEW,"synthetic evidence",25)));
    }
    private static FraudCase aCase(long version,String status,List<FraudRuleSnapshot> rules){
        var at=Instant.parse("2026-08-01T10:00:00Z");
        return new FraudCase(UUID.fromString("00000000-0000-0000-0000-000000000001"),UUID.randomUUID(),"a".repeat(64),
            UUID.fromString("00000000-0000-0000-0000-000000000002"),FraudCaseStatus.valueOf(status),
            version==0?null:"analyst-a",version,at,"b".repeat(64),"merchant-review","7995",new BigDecimal("125.00"),
            "EUR","DE",TransactionChannel.ECOMMERCE,at,NonFraudResult.PASSED,AuthorizationDecision.APPROVED,null,
            FraudAssessment.REVIEW,25,true,at,at,null,null,null,null,
            rules);
    }
}
