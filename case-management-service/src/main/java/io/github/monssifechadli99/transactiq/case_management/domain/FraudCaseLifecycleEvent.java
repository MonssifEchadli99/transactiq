package io.github.monssifechadli99.transactiq.case_management.domain;

import java.time.Instant;
import java.util.UUID;

public record FraudCaseLifecycleEvent(
        UUID eventId,
        String eventType,
        FraudCaseStatus previousStatus,
        FraudCaseStatus resultingStatus,
        String previousAssigneeId,
        String resultingAssigneeId,
        String actorId,
        long caseVersion,
        Instant occurredAt,
        FraudCaseResolutionOutcome resolutionOutcome,
        String resolutionRationale) {}
