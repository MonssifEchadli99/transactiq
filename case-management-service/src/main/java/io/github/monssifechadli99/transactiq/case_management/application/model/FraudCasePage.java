package io.github.monssifechadli99.transactiq.case_management.application.model;

import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseSummary;
import java.util.List;

public record FraudCasePage(List<FraudCaseSummary> items, String nextCursor) {
    public FraudCasePage {
        items = List.copyOf(items);
    }
}
