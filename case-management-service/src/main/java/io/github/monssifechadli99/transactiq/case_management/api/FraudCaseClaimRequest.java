package io.github.monssifechadli99.transactiq.case_management.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record FraudCaseClaimRequest(@NotNull @PositiveOrZero Long expectedVersion) {}
