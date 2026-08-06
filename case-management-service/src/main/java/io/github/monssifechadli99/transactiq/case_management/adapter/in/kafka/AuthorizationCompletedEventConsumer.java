package io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka;

import io.github.monssifechadli99.transactiq.case_management.application.service.FraudCaseCreationService;
import io.github.monssifechadli99.transactiq.case_management.application.service.FraudCaseCreationService.ProcessingResult;
import io.github.monssifechadli99.transactiq.observability.PortfolioMetrics;
import io.github.monssifechadli99.transactiq.observability.PortfolioMetrics.Signal;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

public final class AuthorizationCompletedEventConsumer {

    private final AuthorizationCompletedEventParser parser;
    private final FraudCaseCreationService creationService;
    private final PortfolioMetrics metrics;

    public AuthorizationCompletedEventConsumer(
            AuthorizationCompletedEventParser parser,
            FraudCaseCreationService creationService) {
        this(parser, creationService, PortfolioMetrics.noop());
    }

    public AuthorizationCompletedEventConsumer(
            AuthorizationCompletedEventParser parser,
            FraudCaseCreationService creationService,
            PortfolioMetrics metrics) {
        this.parser = Objects.requireNonNull(parser, "parser must not be null");
        this.creationService = Objects.requireNonNull(
                creationService, "creationService must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    @KafkaListener(
            topics = "${fraud-case.consumer.topic}",
            groupId = "${fraud-case.consumer.group-id}")
    public void consume(ConsumerRecord<byte[], byte[]> record, Acknowledgment acknowledgment) {
        Objects.requireNonNull(record, "record must not be null");
        Objects.requireNonNull(acknowledgment, "acknowledgment must not be null");
        try {
            ProcessingResult result = creationService.process(parser.parse(record.value()));
            metrics.increment(switch (result) {
                case CREATED -> Signal.CASE_CREATED;
                case ALREADY_EXISTS -> Signal.CASE_ALREADY_EXISTS;
                case NOT_REQUIRED -> Signal.CASE_NOT_REQUIRED;
            });
            acknowledgment.acknowledge();
        } catch (RuntimeException failure) {
            metrics.increment(Signal.CASE_FAILED);
            throw failure;
        }
    }
}
