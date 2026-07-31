package io.github.monssifechadli99.transactiq.case_management.adapter.in.web;

import io.github.monssifechadli99.transactiq.case_management.api.FraudCaseApiResponse;
import io.github.monssifechadli99.transactiq.case_management.application.model.FraudCasePage;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCase;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseSummary;
import java.util.stream.IntStream;

public final class FraudCaseApiMapper {
    public FraudCaseApiResponse.Page toPage(FraudCasePage page) {
        return new FraudCaseApiResponse.Page(
                page.items().stream().map(this::toSummary).toList(), page.nextCursor());
    }

    public FraudCaseApiResponse.Detail toDetail(FraudCase fraudCase) {
        var rules = IntStream.range(0, fraudCase.matchedRules().size())
                .mapToObj(index -> {
                    var rule = fraudCase.matchedRules().get(index);
                    return new FraudCaseApiResponse.RuleMatch(
                            index, rule.ruleCode(), rule.severity(), rule.evidence(),
                            rule.scoreContribution());
                }).toList();
        return new FraudCaseApiResponse.Detail(
                fraudCase.caseId(), fraudCase.sourceEventId(), fraudCase.sourceEventHash(),
                fraudCase.requestId(), fraudCase.status(), fraudCase.assigneeId(),
                fraudCase.version(), fraudCase.occurredAt(), fraudCase.cardTokenFingerprint(),
                fraudCase.merchantId(), fraudCase.merchantCategoryCode(), fraudCase.amount(),
                fraudCase.currency(), fraudCase.country(), fraudCase.channel(),
                fraudCase.transactionTime(), fraudCase.nonFraudResult(),
                fraudCase.authorizationDecision(), fraudCase.declineReason(),
                fraudCase.fraudAssessment(), fraudCase.riskScore(), fraudCase.caseRequired(),
                fraudCase.createdAt(), fraudCase.updatedAt(), rules);
    }

    private FraudCaseApiResponse.Summary toSummary(FraudCaseSummary summary) {
        return new FraudCaseApiResponse.Summary(
                summary.caseId(), summary.status(), summary.assigneeId(), summary.version(),
                summary.fraudAssessment(), summary.riskScore(), summary.authorizationDecision(),
                summary.amount(), summary.currency(), summary.merchantId(), summary.occurredAt(),
                summary.createdAt(), summary.updatedAt());
    }
}
