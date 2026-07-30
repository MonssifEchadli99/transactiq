package io.github.monssifechadli99.transactiq.fraud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.fraud.application.port.in.FraudAssessmentUseCase;
import io.github.monssifechadli99.transactiq.fraud.application.service.RuleBasedFraudAssessmentService;
import io.github.monssifechadli99.transactiq.fraud.configuration.GrpcServerLifecycle;
import io.github.monssifechadli99.transactiq.fraud.domain.velocity.VelocityTrackingSettings;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(properties = "fraud.grpc.port=0")
class FraudEngineApplicationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private GrpcServerLifecycle grpcServerLifecycle;

    @Autowired
    private VelocityTrackingSettings velocityTrackingSettings;

    @Test
    void contextProvidesExactlyOneProductionFraudAssessmentUseCase() {
        var useCases = applicationContext.getBeansOfType(FraudAssessmentUseCase.class);

        assertEquals(1, useCases.size());
        assertInstanceOf(RuleBasedFraudAssessmentService.class, useCases.values().iterator().next());
    }

    @Test
    void grpcServerIsRunningOnAnEphemeralPort() {
        assertTrue(grpcServerLifecycle.isRunning());
        assertTrue(grpcServerLifecycle.port() > 0);
    }

    @Test
    void contextUsesApprovedSyntheticVelocityWindowsAndRetention() {
        assertEquals(Duration.ofSeconds(60), velocityTrackingSettings.transactionCountWindow());
        assertEquals(Duration.ofMinutes(5), velocityTrackingSettings.rollingAmountWindow());
        assertEquals(Duration.ofMinutes(10), velocityTrackingSettings.countrySwitchWindow());
        assertEquals(Duration.ofHours(24), velocityTrackingSettings.deduplicationRetention());
    }
}
