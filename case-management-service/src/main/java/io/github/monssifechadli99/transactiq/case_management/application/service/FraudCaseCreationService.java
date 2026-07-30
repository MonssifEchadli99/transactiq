package io.github.monssifechadli99.transactiq.case_management.application.service;

import io.github.monssifechadli99.transactiq.case_management.application.port.out.FraudCaseStore;
import io.github.monssifechadli99.transactiq.case_management.domain.AuthorizationEventSnapshot;
import java.util.Objects;

public final class FraudCaseCreationService {

    private final FraudCaseStore fraudCaseStore;

    public FraudCaseCreationService(FraudCaseStore fraudCaseStore) {
        this.fraudCaseStore = Objects.requireNonNull(fraudCaseStore, "fraudCaseStore must not be null");
    }

    public ProcessingResult process(AuthorizationEventSnapshot event) {
        Objects.requireNonNull(event, "event must not be null");
        if (!event.caseRequired()) {
            return ProcessingResult.NOT_REQUIRED;
        }
        return switch (fraudCaseStore.create(event)) {
            case CREATED -> ProcessingResult.CREATED;
            case ALREADY_EXISTS -> ProcessingResult.ALREADY_EXISTS;
        };
    }

    public enum ProcessingResult {
        NOT_REQUIRED,
        CREATED,
        ALREADY_EXISTS
    }
}
