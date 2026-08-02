package io.github.monssifechadli99.transactiq.case_management.projection;

import io.github.monssifechadli99.transactiq.case_management.adapter.out.jdbc.JdbcFraudCaseLifecycleStore;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseStatus;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class FraudCaseProjectionBootstrap {
    private final JdbcClient jdbc; private final JdbcFraudCaseLifecycleStore cases;
    private final FraudCaseProjectionOutbox outbox; private final int batchSize;
    public FraudCaseProjectionBootstrap(JdbcClient jdbc, JdbcFraudCaseLifecycleStore cases,
            FraudCaseProjectionOutbox outbox, int batchSize){
        this.jdbc=jdbc;this.cases=cases;this.outbox=outbox;this.batchSize=batchSize;
    }
    public Result run(){
        UUID after=new UUID(0,0); long inserted=0,skipped=0,failed=0;
        while(true){
            var statement=jdbc.sql("""
                    SELECT case_id FROM fraud_case.fraud_cases
                    WHERE case_id > :after
                    ORDER BY case_id LIMIT :limit
                    """).param("after",after).param("limit",batchSize);
            var ids=statement.query(UUID.class).list();
            if(ids.isEmpty()) break;
            for(UUID id:ids) try {
                var fraudCase=cases.findById(id).orElseThrow();
                var type=switch(fraudCase.status()){
                    case NEW -> FraudCaseProjectionType.CREATED;
                    case IN_REVIEW -> FraudCaseProjectionType.CLAIMED;
                    case RESOLVED -> FraudCaseProjectionType.RESOLVED;
                };
                if(outbox.append(fraudCase,type)) inserted++; else skipped++;
            } catch(ProjectionIntegrityException conflict){ failed++; throw conflict; }
            after=ids.getLast();
        }
        return new Result(inserted,skipped,failed);
    }
    public record Result(long inserted,long skipped,long failed){}
}
