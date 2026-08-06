package io.github.monssifechadli99.transactiq.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class PortfolioMetricsTest {

    @Test
    void recordsOnlyPredeclaredLowCardinalityTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PortfolioMetrics metrics = new PortfolioMetrics(registry);

        metrics.increment(PortfolioMetrics.Signal.AUTHORIZATION_APPROVED);
        metrics.increment(PortfolioMetrics.Signal.INVESTIGATION_ANSWER_GROUNDED);

        assertThat(registry.get("transactiq.authorization.processed")
                        .tags("result", "completed", "decision", "approved")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("transactiq.investigation.processed")
                        .tags("operation", "answer", "result", "grounded")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.getMeters()).hasSize(2);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags())
                        .extracting(tag -> tag.getKey())
                        .allMatch(key -> key.equals("operation")
                                || key.equals("result")
                                || key.equals("decision")));
    }

    @Test
    void metricIdentifiersCannotContainRequestOrEvidenceValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PortfolioMetrics metrics = new PortfolioMetrics(registry);

        metrics.increment(PortfolioMetrics.Signal.FRAUD_UNAVAILABLE);

        assertThat(registry.getMeters())
                .extracting(meter -> meter.getId().toString())
                .allSatisfy(id -> assertThat(id)
                        .doesNotContain("question", "evidence", "prompt", "credential"));
    }

    @Test
    void registryFailureCannotChangeBusinessProcessing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                throw new IllegalStateException("synthetic registry failure");
            }
        });
        PortfolioMetrics metrics = new PortfolioMetrics(registry);

        assertThatCode(() -> metrics.increment(PortfolioMetrics.Signal.CASE_CREATED))
                .doesNotThrowAnyException();
        assertThat(registry.getMeters()).isEmpty();
    }
}
