package io.github.monssifechadli99.transactiq.authorization.configuration;

import io.github.monssifechadli99.transactiq.authorization.adapter.out.memory.DeterministicFraudAssessmentAdapter;
import io.github.monssifechadli99.transactiq.authorization.adapter.out.memory.DeterministicNonFraudCheckAdapter;
import io.github.monssifechadli99.transactiq.authorization.adapter.out.memory.InMemoryAuthorizationLedgerAdapter;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.AuthorizationLedgerPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.FraudAssessmentPort;
import io.github.monssifechadli99.transactiq.authorization.application.port.out.NonFraudCheckPort;
import io.github.monssifechadli99.transactiq.authorization.application.service.AuthorizationApplicationService;
import io.github.monssifechadli99.transactiq.authorization.domain.AuthorizationPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AuthorizationConfiguration {

    @Bean
    DeterministicFraudAssessmentAdapter fraudAssessmentAdapter() {
        return new DeterministicFraudAssessmentAdapter();
    }

    @Bean
    DeterministicNonFraudCheckAdapter nonFraudCheckAdapter() {
        return new DeterministicNonFraudCheckAdapter();
    }

    @Bean
    InMemoryAuthorizationLedgerAdapter authorizationLedgerAdapter() {
        return new InMemoryAuthorizationLedgerAdapter();
    }

    @Bean
    AuthorizationPolicy authorizationPolicy() {
        return new AuthorizationPolicy();
    }

    @Bean
    AuthorizationApplicationService authorizationApplicationService(
            FraudAssessmentPort fraudAssessmentPort,
            NonFraudCheckPort nonFraudCheckPort,
            AuthorizationLedgerPort authorizationLedgerPort,
            AuthorizationPolicy authorizationPolicy) {
        return new AuthorizationApplicationService(
                fraudAssessmentPort,
                nonFraudCheckPort,
                authorizationLedgerPort,
                authorizationPolicy);
    }
}
