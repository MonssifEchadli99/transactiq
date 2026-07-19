package io.github.monssifechadli99.transactiq.authorization.adapter.out.memory;

import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.FraudAssessmentPort;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessment;
import java.util.Objects;

public final class DeterministicFraudAssessmentAdapter implements FraudAssessmentPort {

    private static final String REVIEW_MERCHANT_ID = "merchant-review";
    private static final String HIGH_RISK_MERCHANT_ID = "merchant-high-risk";

    @Override
    public FraudAssessment assess(AuthorizationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String merchantId = Objects.requireNonNull(
                command.merchantId(),
                "merchantId must not be null");

        return switch (merchantId) {
            case REVIEW_MERCHANT_ID -> FraudAssessment.REVIEW;
            case HIGH_RISK_MERCHANT_ID -> FraudAssessment.HIGH_RISK;
            default -> FraudAssessment.CLEAR;
        };
    }
}
