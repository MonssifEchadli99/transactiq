package io.github.monssifechadli99.transactiq.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Records only pre-declared, low-cardinality signals; callers cannot attach sensitive tags. */
public final class PortfolioMetrics {

    private static final PortfolioMetrics NOOP = new PortfolioMetrics();

    private final MeterRegistry registry;
    private final Map<Signal, Counter> counters;

    public PortfolioMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.counters = new ConcurrentHashMap<>();
    }

    private PortfolioMetrics() {
        this.registry = null;
        this.counters = Map.of();
    }

    /** Used by compatibility constructors in isolated unit tests. */
    public static PortfolioMetrics noop() {
        return NOOP;
    }

    public void increment(Signal signal) {
        Objects.requireNonNull(signal, "signal must not be null");
        if (registry == null) {
            return;
        }
        try {
            counters.computeIfAbsent(signal, this::register).increment();
        } catch (RuntimeException ignored) {
            // Telemetry must never change an authorization, retry, or investigation outcome.
        }
    }

    private Counter register(Signal signal) {
        return Counter.builder(signal.metricName())
                .description(signal.description())
                .tags(signal.tags())
                .register(registry);
    }

    public enum Signal {
        AUTHORIZATION_APPROVED("transactiq.authorization.processed", "completed", "approved"),
        AUTHORIZATION_DECLINED("transactiq.authorization.processed", "completed", "declined"),
        AUTHORIZATION_PENDING("transactiq.authorization.processed", "pending", "not_applicable"),
        AUTHORIZATION_CONFLICT("transactiq.authorization.processed", "conflict", "not_applicable"),

        FRAUD_CLEAR("transactiq.fraud.assessed", "clear"),
        FRAUD_REVIEW("transactiq.fraud.assessed", "review"),
        FRAUD_HIGH_RISK("transactiq.fraud.assessed", "high_risk"),
        FRAUD_INVALID("transactiq.fraud.assessed", "invalid_input"),
        FRAUD_CONFLICT("transactiq.fraud.assessed", "request_conflict"),
        FRAUD_UNAVAILABLE("transactiq.fraud.assessed", "velocity_unavailable"),

        CASE_CREATED("transactiq.case.event.processed", "created"),
        CASE_ALREADY_EXISTS("transactiq.case.event.processed", "already_exists"),
        CASE_NOT_REQUIRED("transactiq.case.event.processed", "not_required"),
        CASE_FAILED("transactiq.case.event.processed", "failed"),

        INVESTIGATION_RETRIEVED("transactiq.investigation.processed", "retrieval", "retrieved"),
        INVESTIGATION_RETRIEVAL_MISSING(
                "transactiq.investigation.processed", "retrieval", "missing_evidence"),
        INVESTIGATION_RETRIEVAL_UNAVAILABLE(
                "transactiq.investigation.processed", "retrieval", "unavailable"),
        INVESTIGATION_ANSWER_GROUNDED(
                "transactiq.investigation.processed", "answer", "grounded"),
        INVESTIGATION_ANSWER_INSUFFICIENT(
                "transactiq.investigation.processed", "answer", "insufficient_evidence"),
        INVESTIGATION_ANSWER_MISSING(
                "transactiq.investigation.processed", "answer", "missing_evidence"),
        INVESTIGATION_ANSWER_UNAVAILABLE(
                "transactiq.investigation.processed", "answer", "unavailable");

        private final String metricName;
        private final String[] tagValues;

        Signal(String metricName, String... tagValues) {
            this.metricName = metricName;
            this.tagValues = tagValues;
        }

        String metricName() {
            return metricName;
        }

        String description() {
            return "TransactIQ bounded portfolio business signal";
        }

        Tags tags() {
            if (metricName.equals("transactiq.authorization.processed")) {
                return Tags.of("result", tagValues[0], "decision", tagValues[1]);
            }
            if (metricName.equals("transactiq.investigation.processed")) {
                return Tags.of("operation", tagValues[0], "result", tagValues[1]);
            }
            return Tags.of("result", tagValues[0]);
        }
    }
}
