package io.github.monssifechadli99.transactiq.investigation_assistant.adapter.in.kafka;

import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudCaseProjectionEvent;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.ProjectionIngestionService;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.ProjectionValidator;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.InvalidProjectionException;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.ValidatedProjection;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * Consumes the existing fraud-case projection topic under a dedicated consumer group
 * so this module's read/replay position never interferes with case-search-service.
 * The listener container acknowledges (ack-mode record) only after {@code ingest}
 * returns normally, i.e. only after successful indexing.
 */
public final class InvestigationProjectionConsumer {

    private final ProjectionValidator validator;
    private final ProjectionIngestionService ingestionService;

    public InvestigationProjectionConsumer(
            ProjectionValidator validator, ProjectionIngestionService ingestionService) {
        this.validator = validator;
        this.ingestionService = ingestionService;
    }

    @KafkaListener(
            topics = "${investigation-assistant.consumer.topic}",
            groupId = "${investigation-assistant.consumer.group-id}")
    public void consume(ConsumerRecord<byte[], byte[]> record) {
        FraudCaseProjectionEvent event;
        try {
            event = FraudCaseProjectionEvent.parseFrom(record.value());
        } catch (Exception error) {
            throw new InvalidProjectionException("projection payload is not valid Protobuf");
        }
        ValidatedProjection projection = validator.validateProjection(record.key(), event);
        ingestionService.ingest(projection);
    }
}
