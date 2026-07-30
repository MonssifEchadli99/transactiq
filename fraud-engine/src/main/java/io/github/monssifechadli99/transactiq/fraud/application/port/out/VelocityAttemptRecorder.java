package io.github.monssifechadli99.transactiq.fraud.application.port.out;

import io.github.monssifechadli99.transactiq.fraud.domain.FraudAssessmentRequest;
import io.github.monssifechadli99.transactiq.fraud.domain.velocity.VelocitySnapshot;
import java.time.Instant;

public interface VelocityAttemptRecorder {

    VelocitySnapshot recordAttemptAndGetSnapshot(
            FraudAssessmentRequest request,
            Instant observedAt);
}
