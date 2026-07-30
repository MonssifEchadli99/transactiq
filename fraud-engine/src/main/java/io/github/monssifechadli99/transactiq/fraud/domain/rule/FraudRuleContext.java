package io.github.monssifechadli99.transactiq.fraud.domain.rule;

import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentRequest;
import io.github.monssifechadli99.transactiq.fraud.domain.velocity.VelocitySnapshot;
import java.util.Objects;

public record FraudRuleContext(
        FraudAssessmentRequest request,
        VelocitySnapshot velocitySnapshot) {

    public FraudRuleContext {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(velocitySnapshot, "velocitySnapshot must not be null");
    }
}
