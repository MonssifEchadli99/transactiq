package io.github.monssifechadli99.transactiq.case_management.projection;

import org.springframework.scheduling.annotation.Scheduled;

public final class FraudCaseProjectionScheduler {
    private final FraudCaseProjectionRelay relay;
    public FraudCaseProjectionScheduler(FraudCaseProjectionRelay relay){this.relay=relay;}
    @Scheduled(fixedDelayString="${fraud-case.projection.poll-interval:1s}")
    public void publish(){relay.publishDue();}
}
