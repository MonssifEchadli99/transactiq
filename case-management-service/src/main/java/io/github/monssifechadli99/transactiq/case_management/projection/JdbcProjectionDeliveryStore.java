package io.github.monssifechadli99.transactiq.case_management.projection;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcProjectionDeliveryStore {
    private final JdbcClient jdbc; private final TransactionOperations transactions;
    public JdbcProjectionDeliveryStore(JdbcClient jdbc, TransactionOperations transactions) {
        this.jdbc=jdbc; this.transactions=transactions;
    }
    public ProjectionPartitionOwner acquire(String topic, int partition, Duration lease) {
        UUID token = UUID.randomUUID();
        return transactions.execute(ignored -> jdbc.sql("""
                INSERT INTO fraud_case.projection_partition_ownership
                    (topic,partition_number,owner_token,generation,lease_until,acquired_at,renewed_at)
                VALUES (:topic,:partition,:token,1,clock_timestamp()+(:leaseMillis * interval '1 millisecond'),
                        clock_timestamp(),clock_timestamp())
                ON CONFLICT (topic,partition_number) DO UPDATE
                SET owner_token=:token,generation=projection_partition_ownership.generation+1,
                    lease_until=clock_timestamp()+(:leaseMillis * interval '1 millisecond'),
                    acquired_at=clock_timestamp(),renewed_at=clock_timestamp()
                WHERE projection_partition_ownership.lease_until<=clock_timestamp()
                RETURNING topic,partition_number,owner_token,generation
                """).param("topic",topic).param("partition",partition).param("token",token)
                .param("leaseMillis",lease.toMillis())
                .query((rs,row)->new ProjectionPartitionOwner(rs.getString(1),rs.getInt(2),
                        rs.getObject(3,UUID.class),rs.getLong(4))).optional().orElse(null));
    }

    public boolean renew(ProjectionPartitionOwner owner, Duration lease) {
        return jdbc.sql("""
                UPDATE fraud_case.projection_partition_ownership
                SET lease_until=clock_timestamp()+(:leaseMillis * interval '1 millisecond'),renewed_at=clock_timestamp()
                WHERE topic=:topic AND partition_number=:partition AND owner_token=:token
                  AND generation=:generation AND lease_until>clock_timestamp()
                """).param("leaseMillis",lease.toMillis()).param("topic",owner.topic())
                .param("partition",owner.partition()).param("token",owner.token())
                .param("generation",owner.generation()).update()==1;
    }

    public void release(ProjectionPartitionOwner owner) {
        jdbc.sql("""
                UPDATE fraud_case.projection_partition_ownership SET lease_until=clock_timestamp()
                WHERE topic=:topic AND partition_number=:partition AND owner_token=:token AND generation=:generation
                """).param("topic",owner.topic()).param("partition",owner.partition())
                .param("token",owner.token()).param("generation",owner.generation()).update();
    }

    public List<ClaimedProjectionEvent> claim(
            ProjectionPartitionOwner owner, int limit, Instant now, Duration lease) {
        UUID token=UUID.randomUUID();
        List<ClaimedProjectionEvent> result=transactions.execute(ignored -> jdbc.sql("""
                WITH due AS (
                  SELECT candidate.event_id
                  FROM fraud_case.fraud_case_projection_outbox candidate
                   WHERE ((candidate.publication_state='PENDING' AND candidate.next_attempt_at<=:now)
                      OR (candidate.publication_state='IN_FLIGHT' AND candidate.lease_until<=:now))
                     AND candidate.topic_partition=:partition
                     AND EXISTS (SELECT 1 FROM fraud_case.projection_partition_ownership ownership
                       WHERE ownership.topic=:topic AND ownership.partition_number=:partition
                         AND ownership.owner_token=:ownerToken AND ownership.generation=:generation
                         AND ownership.lease_until>clock_timestamp())
                    AND NOT EXISTS (
                      SELECT 1 FROM fraud_case.fraud_case_projection_outbox lower_version
                      WHERE lower_version.fraud_case_id=candidate.fraud_case_id
                        AND lower_version.aggregate_version<candidate.aggregate_version
                        AND lower_version.publication_state<>'PUBLISHED')
                  ORDER BY candidate.created_at,candidate.event_id FOR UPDATE SKIP LOCKED LIMIT :limit)
                UPDATE fraud_case.fraud_case_projection_outbox o
                SET publication_state='IN_FLIGHT',lease_token=:token,lease_until=:until
                FROM due WHERE o.event_id=due.event_id
                RETURNING o.event_id,o.fraud_case_id,o.aggregate_version,o.payload,o.attempt_count
                """).param("now", db(now)).param("limit", limit).param("token", token)
                .param("topic",owner.topic()).param("partition",owner.partition())
                .param("ownerToken",owner.token()).param("generation",owner.generation())
                .param("until", db(now.plus(lease))).query((rs,row)->new ClaimedProjectionEvent(
                        rs.getObject(1,UUID.class),token,rs.getObject(2,UUID.class),rs.getLong(3),rs.getBytes(4),rs.getInt(5))).list());
        return result == null ? List.of() : result;
    }
    public boolean published(ProjectionPartitionOwner owner, ClaimedProjectionEvent event, Instant at) {
        return transactions.execute(ignored -> jdbc.sql("""
            UPDATE fraud_case.fraud_case_projection_outbox o SET publication_state='PUBLISHED',published_at=:at,
            lease_token=NULL,lease_until=NULL,last_error_code=NULL
            FROM fraud_case.projection_partition_ownership ownership
            WHERE o.event_id=:id AND o.publication_state='IN_FLIGHT' AND o.lease_token=:rowToken
              AND ownership.topic=:topic AND ownership.partition_number=:partition
              AND ownership.owner_token=:ownerToken AND ownership.generation=:generation
              AND ownership.lease_until>clock_timestamp()
            """).param("at",db(at)).param("id",event.eventId()).param("rowToken",event.leaseToken())
            .param("topic",owner.topic()).param("partition",owner.partition())
            .param("ownerToken",owner.token()).param("generation",owner.generation()).update()==1); }
    public boolean failed(ProjectionPartitionOwner owner, ClaimedProjectionEvent event, Instant next) { return jdbc.sql("""
            UPDATE fraud_case.fraud_case_projection_outbox SET publication_state='PENDING',attempt_count=attempt_count+1,
            next_attempt_at=:next,lease_token=NULL,lease_until=NULL,last_error_code='KAFKA_PUBLISH_FAILED'
            WHERE event_id=:id AND publication_state='IN_FLIGHT' AND lease_token=:rowToken
              AND EXISTS (SELECT 1 FROM fraud_case.projection_partition_ownership ownership
                WHERE ownership.topic=:topic AND ownership.partition_number=:partition
                  AND ownership.owner_token=:ownerToken AND ownership.generation=:generation)
            """).param("next",db(next)).param("id",event.eventId()).param("rowToken",event.leaseToken())
            .param("topic",owner.topic()).param("partition",owner.partition())
            .param("ownerToken",owner.token()).param("generation",owner.generation()).update()==1; }
    private static OffsetDateTime db(Instant i){return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);}
}
