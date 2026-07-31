package io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka;

import io.github.monssifechadli99.transactiq.case_management.application.service.FraudCaseCreationService;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

public final class AuthorizationCompletedEventConsumer {

    private final AuthorizationCompletedEventParser parser;
    private final FraudCaseCreationService creationService;

    public AuthorizationCompletedEventConsumer(
            AuthorizationCompletedEventParser parser,
            FraudCaseCreationService creationService) {
        this.parser = Objects.requireNonNull(parser, "parser must not be null");
        this.creationService = Objects.requireNonNull(
                creationService, "creationService must not be null");
    }

    @KafkaListener(
            topics = "${fraud-case.consumer.topic}",
            groupId = "${fraud-case.consumer.group-id}")
    public void consume(ConsumerRecord<byte[], byte[]> record, Acknowledgment acknowledgment) {
        Objects.requireNonNull(record, "record must not be null");
        Objects.requireNonNull(acknowledgment, "acknowledgment must not be null");
        creationService.process(parser.parse(record.value()));
        acknowledgment.acknowledge();
    }
}
