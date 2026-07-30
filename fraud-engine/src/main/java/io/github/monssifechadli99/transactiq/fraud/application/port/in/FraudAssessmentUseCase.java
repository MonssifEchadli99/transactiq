package io.github.monssifechadli99.transactiq.fraud.application.port.in;

import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentRequest;
import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentResult;

public interface FraudAssessmentUseCase {

    FraudAssessmentResult assess(FraudAssessmentRequest request);
}
