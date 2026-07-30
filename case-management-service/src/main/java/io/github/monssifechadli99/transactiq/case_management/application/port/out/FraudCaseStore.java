package io.github.monssifechadli99.transactiq.case_management.application.port.out;

import io.github.monssifechadli99.transactiq.case_management.domain.AuthorizationEventSnapshot;

public interface FraudCaseStore {

    CreationResult create(AuthorizationEventSnapshot event);

    enum CreationResult {
        CREATED,
        ALREADY_EXISTS
    }
}
