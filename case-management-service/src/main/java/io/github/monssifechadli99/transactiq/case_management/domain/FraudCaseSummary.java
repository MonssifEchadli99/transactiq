package io.github.monssifechadli99.transactiq.case_management.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record FraudCaseSummary(
        UUID caseId,
        FraudCaseStatus status,
        String assigneeId,
        long version,
        FraudAssessment fraudAssessment,
        int riskScore,
        AuthorizationDecision authorizationDecision,
        BigDecimal amount,
        String currency,
        String merchantId,
        Instant occurredAt,
        Instant createdAt,
        Instant updatedAt,
        FraudCaseResolutionOutcome resolutionOutcome) {}
