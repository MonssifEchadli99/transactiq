package io.github.monssifechadli99.transactiq.case_management.projection;

import static org.junit.jupiter.api.Assertions.*;
import io.github.monssifechadli99.transactiq.case_management.configuration.FraudCaseConfiguration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

class FraudCaseBootstrapIsolationConfigurationTest {
    @Test void bootstrapModeDisablesScheduledRelayIndependentOfListenerSetting(){
        var method=Arrays.stream(FraudCaseConfiguration.class.getDeclaredMethods())
                .filter(candidate->candidate.getName().equals("fraudCaseProjectionScheduler"))
                .findFirst().orElseThrow();
        var condition=method.getAnnotation(ConditionalOnExpression.class);
        assertNotNull(condition);
        assertTrue(condition.value().contains("fraud-case.projection.bootstrap-enabled:false"));
        assertTrue(condition.value().contains("== 'false'"));
    }

    @Test void bootstrapRunnerIsAnApplicationRunnerThatOwnsDeterministicExit(){
        assertTrue(org.springframework.boot.ApplicationRunner.class.isAssignableFrom(
                FraudCaseProjectionBootstrapRunner.class));
        assertTrue(Arrays.stream(FraudCaseProjectionBootstrapRunner.class.getDeclaredFields())
                .anyMatch(field->field.getType().equals(org.springframework.context.ConfigurableApplicationContext.class)));
    }
}
