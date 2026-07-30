package io.github.monssifechadli99.transactiq.authorization.support;

import io.github.monssifechadli99.transactiq.authorization.adapter.out.memory.DeterministicFraudAssessmentAdapter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class FraudAssessmentTestConfiguration {

    @Bean
    DeterministicFraudAssessmentAdapter deterministicFraudAssessmentAdapter() {
        return new DeterministicFraudAssessmentAdapter();
    }
}
