package io.github.monssifechadli99.transactiq.case_management.api;

import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseResolutionOutcome;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record FraudCaseResolveRequest(
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull FraudCaseResolutionOutcome outcome,
        @NotNull String rationale) {}
