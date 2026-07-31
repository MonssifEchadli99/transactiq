package io.github.monssifechadli99.transactiq.case_management.application.service;

import io.github.monssifechadli99.transactiq.case_management.application.model.FraudCaseClaimResult;
import io.github.monssifechadli99.transactiq.case_management.application.model.FraudCasePage;
import io.github.monssifechadli99.transactiq.case_management.application.model.FraudCaseQuery;
import io.github.monssifechadli99.transactiq.case_management.application.port.out.FraudCaseLifecycleStore;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCase;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseAssignmentFilter;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseStatus;
import java.util.UUID;

public final class FraudCaseLifecycleService {
    private final FraudCaseLifecycleStore store;
    private final FraudCaseCursorCodec cursorCodec;

    public FraudCaseLifecycleService(FraudCaseLifecycleStore store, FraudCaseCursorCodec cursorCodec) {
        this.store = store;
        this.cursorCodec = cursorCodec;
    }

    public FraudCasePage list(
            FraudCaseStatus status,
            FraudCaseAssignmentFilter assignment,
            String analystHeader,
            int pageSize,
            String cursor) {
        if (pageSize < 1 || pageSize > 100) {
            throw new InvalidFraudCaseRequestException("INVALID_PAGE_SIZE");
        }
        FraudCaseAssignmentFilter effectiveAssignment = assignment == null
                ? FraudCaseAssignmentFilter.ANY : assignment;
        String analystId = effectiveAssignment == FraudCaseAssignmentFilter.MINE
                ? AnalystIdentity.required(analystHeader) : null;
        FraudCaseCursorCodec.Cursor decoded = cursor == null || cursor.isBlank()
                ? null : cursorCodec.decode(cursor);
        var rows = store.findPage(new FraudCaseQuery(
                status, effectiveAssignment, analystId, pageSize + 1,
                decoded == null ? null : decoded.createdAt(),
                decoded == null ? null : decoded.caseId()));
        boolean hasNext = rows.size() > pageSize;
        var items = hasNext ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasNext
                ? cursorCodec.encode(items.getLast().createdAt(), items.getLast().caseId())
                : null;
        return new FraudCasePage(items, nextCursor);
    }

    public FraudCase details(UUID caseId) {
        return store.findById(caseId).orElseThrow(FraudCaseNotFoundException::new);
    }

    public FraudCase claim(UUID caseId, String analystHeader, long expectedVersion) {
        if (expectedVersion < 0) {
            throw new InvalidFraudCaseRequestException("INVALID_EXPECTED_VERSION");
        }
        String analystId = AnalystIdentity.required(analystHeader);
        FraudCaseClaimResult result = store.claim(caseId, analystId, expectedVersion);
        return switch (result.outcome()) {
            case CLAIMED, ALREADY_CLAIMED_BY_ANALYST -> result.fraudCase();
            case NOT_FOUND -> throw new FraudCaseNotFoundException();
            case ALREADY_ASSIGNED -> throw new FraudCaseConflictException("CASE_ALREADY_ASSIGNED");
            case VERSION_CONFLICT -> throw new FraudCaseConflictException("CASE_VERSION_CONFLICT");
            case NOT_CLAIMABLE -> throw new FraudCaseConflictException("CASE_NOT_CLAIMABLE");
        };
    }
}
