package io.github.monssifechadli99.transactiq.authorization.adapter.out.memory;

import io.github.monssifechadli99.transactiq.authorization.application.port.in.AuthorizationCommand;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.FraudAssessmentPort;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessment;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudAssessmentResult;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudRuleMatch;
import io.github.monssifechadli99.transactiq.authorization.domain.FraudRuleSeverity;
import java.util.List;
import java.util.Objects;

public final class DeterministicFraudAssessmentAdapter implements FraudAssessmentPort {

    private static final String REVIEW_MERCHANT_ID = "merchant-review";
    private static final String HIGH_RISK_MERCHANT_ID = "merchant-high-risk";

    @Override
    public FraudAssessmentResult assess(AuthorizationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String merchantId = Objects.requireNonNull(
                command.merchantId(),
                "merchantId must not be null");

        return switch (merchantId) {
            case REVIEW_MERCHANT_ID -> new FraudAssessmentResult(
                    FraudAssessment.REVIEW,
                    15,
                    List.of(new FraudRuleMatch(
                            "TEST_MERCHANT_REVIEW",
                            FraudRuleSeverity.REVIEW,
                            "Synthetic test merchant requires review",
                            15)));
            case HIGH_RISK_MERCHANT_ID -> new FraudAssessmentResult(
                    FraudAssessment.HIGH_RISK,
                    75,
                    List.of(new FraudRuleMatch(
                            "TEST_MERCHANT_HIGH_RISK",
                            FraudRuleSeverity.HIGH_RISK,
                            "Synthetic test merchant is high risk",
                            75)));
            default -> FraudAssessmentResult.clear();
        };
    }
}
