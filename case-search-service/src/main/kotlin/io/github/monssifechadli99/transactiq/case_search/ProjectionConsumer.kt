package io.github.monssifechadli99.transactiq.case_search

import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudCaseProjectionEvent
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener

class ProjectionConsumer(private val validator:ProjectionValidator,private val documents:ProjectionDocumentMapper,
    private val store:OpenSearchProjectionStore) {
    @KafkaListener(topics=["\${case-search.topic}"],groupId="\${spring.kafka.consumer.group-id}")
    fun consume(record:ConsumerRecord<ByteArray,ByteArray>){
        val event=try{FraudCaseProjectionEvent.parseFrom(record.value())}
        catch(error:Exception){throw InvalidProjectionException("projection payload is not valid Protobuf")}
        val snapshot=validator.validate(record.key()?:byteArrayOf(),event)
        store.apply(event.caseId,documents.map(event,snapshot))
    }
}
