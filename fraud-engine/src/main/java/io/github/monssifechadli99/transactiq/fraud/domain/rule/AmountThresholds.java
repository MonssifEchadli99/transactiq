package io.github.monssifechadli99.transactiq.fraud.domain.rule;

import java.math.BigDecimal;
import java.util.Objects;

public record AmountThresholds(BigDecimal review, BigDecimal highRisk) {

    public AmountThresholds {
        Objects.requireNonNull(review, "review threshold must not be null");
        Objects.requireNonNull(highRisk, "highRisk threshold must not be null");
        if (review.signum() <= 0) {
            throw new IllegalArgumentException("review threshold must be positive");
        }
        if (highRisk.signum() <= 0) {
            throw new IllegalArgumentException("highRisk threshold must be positive");
        }
        if (highRisk.compareTo(review) <= 0) {
            throw new IllegalArgumentException("highRisk threshold must be greater than review threshold");
        }
    }
}
