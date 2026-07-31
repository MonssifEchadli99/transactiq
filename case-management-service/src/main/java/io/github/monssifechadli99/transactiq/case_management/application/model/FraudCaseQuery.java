package io.github.monssifechadli99.transactiq.case_management.application.model;

import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseAssignmentFilter;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseStatus;
import java.time.Instant;
import java.util.UUID;

public record FraudCaseQuery(
        FraudCaseStatus status,
        FraudCaseAssignmentFilter assignment,
        String analystId,
        int pageSize,
        Instant afterCreatedAt,
        UUID afterCaseId) {}
