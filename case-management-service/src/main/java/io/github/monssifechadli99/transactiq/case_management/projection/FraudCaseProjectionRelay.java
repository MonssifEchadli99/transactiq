package io.github.monssifechadli99.transactiq.case_management.projection;

import io.github.monssifechadli99.transactiq.case_management.configuration.FraudCaseProjectionProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.OutOfOrderSequenceException;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FraudCaseProjectionRelay {
    private static final Logger log=LoggerFactory.getLogger(FraudCaseProjectionRelay.class);
    private final JdbcProjectionDeliveryStore store;
    private final ProjectionTransactionalProducerFactory producers;
    private final FraudCaseProjectionProperties properties;
    private final Clock clock;
    private final ProjectionPublicationObserver observer;

    public FraudCaseProjectionRelay(JdbcProjectionDeliveryStore store,
            ProjectionTransactionalProducerFactory producers,
            FraudCaseProjectionProperties properties, Clock clock) {
        this(store,producers,properties,clock,ProjectionPublicationObserver.NONE);
    }

    public FraudCaseProjectionRelay(JdbcProjectionDeliveryStore store,
            ProjectionTransactionalProducerFactory producers,
            FraudCaseProjectionProperties properties, Clock clock,
            ProjectionPublicationObserver observer) {
        this.store=store;this.producers=producers;this.properties=properties;this.clock=clock;
        this.observer=observer;
    }

    public void publishDue() {
        for (int partition=0; partition<properties.topicPartitions(); partition++) publishPartition(partition);
    }

    void publishPartition(int partition) {
        ProjectionPartitionOwner owner=store.acquire(properties.topic(),partition,properties.leaseDuration());
        if(owner==null) return;
        observer.afterOwnershipAcquired(owner);
        try(ProjectionTransactionalProducer producer=producers.create(
                properties.topic(),partition,properties.transactionalId(partition))) {
            producer.initTransactions();
            observer.afterProducerInitialized(owner);
            if(!store.renew(owner,properties.leaseDuration())) return;
            while(true) {
                if(!store.renew(owner,properties.leaseDuration())) return;
                var events=store.claim(owner,1,clock.instant(),properties.leaseDuration());
                if(events.isEmpty()) return;
                if(!publishOne(owner,producer,events.getFirst())) return;
            }
        } catch (ProducerFencedException|OutOfOrderSequenceException fatal) {
            log.warn("fraud_case_projection_owner_fenced topic={} partition={} generation={}",
                    owner.topic(),owner.partition(),owner.generation(),fatal);
        } catch (KafkaException fatalOrUncertain) {
            log.warn("fraud_case_projection_producer_lost topic={} partition={} generation={}",
                    owner.topic(),owner.partition(),owner.generation(),fatalOrUncertain);
        } finally {
            store.release(owner);
        }
    }

    private boolean publishOne(ProjectionPartitionOwner owner, ProjectionTransactionalProducer producer,
            ClaimedProjectionEvent event) {
        boolean transactionStarted=false;
        try {
            if(!store.renew(owner,properties.leaseDuration())) return false;
            producer.beginTransaction(); transactionStarted=true;
            observer.beforeSend(owner,event);
            producer.send(owner.partition(),event.caseId().toString().getBytes(StandardCharsets.UTF_8),event.payload());
            observer.beforeCommit(owner,event);
            producer.commitTransaction(); transactionStarted=false;
            observer.afterCommitBeforeMark(owner,event);
            return store.published(owner,event,clock.instant());
        } catch (ProducerFencedException|OutOfOrderSequenceException fatal) {
            log.warn("fraud_case_projection_transaction_fenced eventId={} generation={}",
                    event.eventId(),owner.generation(),fatal);
            return false;
        } catch (Exception failure) {
            if(transactionStarted) try { producer.abortTransaction(); } catch (RuntimeException ignored) { return false; }
            store.failed(owner,event,clock.instant().plus(backoff(event.attemptCount())));
            log.warn("fraud_case_projection_publish_failed eventId={} generation={}",
                    event.eventId(),owner.generation(),failure);
            return false;
        }
    }

    private Duration backoff(int attempts){
        Duration value=properties.retryInitialBackoff();
        for(int i=0;i<attempts && value.compareTo(properties.retryMaximumBackoff())<0;i++)
            value=value.compareTo(properties.retryMaximumBackoff().dividedBy(2))>0
                    ?properties.retryMaximumBackoff():value.multipliedBy(2);
        return value;
    }
}
