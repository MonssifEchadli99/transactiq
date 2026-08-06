package io.github.monssifechadli99.transactiq.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class ObservabilityAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void registersCorrelationFilterAndMetricsInServletApplications() {
        contextRunner.run(context -> {
            org.assertj.core.api.Assertions.assertThat(context)
                    .hasSingleBean(RequestCorrelationFilter.class)
                    .hasSingleBean(PortfolioMetrics.class);
        });
    }
}
