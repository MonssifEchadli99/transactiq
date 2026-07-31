package io.github.monssifechadli99.transactiq.case_management.application.port.out;

import io.github.monssifechadli99.transactiq.case_management.application.model.FraudCaseClaimResult;
import io.github.monssifechadli99.transactiq.case_management.application.model.FraudCaseQuery;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCase;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseSummary;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FraudCaseLifecycleStore {
    List<FraudCaseSummary> findPage(FraudCaseQuery query);
    Optional<FraudCase> findById(UUID caseId);
    FraudCaseClaimResult claim(UUID caseId, String analystId, long expectedVersion);
}
