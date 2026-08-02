package io.github.monssifechadli99.transactiq.case_management.projection;

import static io.github.monssifechadli99.transactiq.case_management.support.AuthorizationEventFixtures.reviewEvent;
import static org.junit.jupiter.api.Assertions.*;

import io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka.AuthorizationCompletedEventParser;
import io.github.monssifechadli99.transactiq.case_management.adapter.out.jdbc.JdbcFraudCaseLifecycleStore;
import io.github.monssifechadli99.transactiq.case_management.application.port.out.FraudCaseStore;
import io.github.monssifechadli99.transactiq.case_management.configuration.FraudCaseProjectionProperties;
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudCaseProjectionEvent;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(properties={"spring.kafka.listener.auto-startup=false",
        "fraud-case.projection.topic=case-projection-fencing","fraud-case.projection.topic-partitions=1",
        "fraud-case.projection.environment=test","fraud-case.projection.poll-interval=1h",
        "fraud-case.projection.lease-duration=2s","fraud-case.projection.producer-operation-timeout=5s"})
@Timeout(90)
class FraudCaseProjectionFencingIntegrationTest {
    @Container static final KafkaContainer KAFKA=new KafkaContainer(DockerImageName.parse("apache/kafka-native:4.1.0"));
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("postgres:18.4-alpine3.24"))
            .withDatabaseName("transactiq_case_management").withUsername("case_test").withPassword("case_test");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){
        registry.add("spring.kafka.bootstrap-servers",KAFKA::getBootstrapServers);
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
    }

    @Autowired FraudCaseStore cases;
    @Autowired JdbcFraudCaseLifecycleStore lifecycle;
    @Autowired JdbcProjectionDeliveryStore store;
    @Autowired ProducerFactory<Object,Object> springProducerFactory;
    @Autowired JdbcClient jdbc;
    @Autowired FraudCaseProjectionProperties properties;
    private final AuthorizationCompletedEventParser parser=new AuthorizationCompletedEventParser();

    @BeforeEach void reset(){
        jdbc.sql("TRUNCATE fraud_case.fraud_cases CASCADE").update();
        jdbc.sql("TRUNCATE fraud_case.projection_partition_ownership").update();
    }

    @Test void delayedBeforeSendIsFencedAndCommittedVersionsNeverDecrease() throws Exception {
        assertFencedSequence(PausePoint.BEFORE_SEND,List.of(0L,1L));
    }

    @Test void delayedBeforeCommitIsAbortedAndReadCommittedVersionsNeverDecrease() throws Exception {
        assertFencedSequence(PausePoint.BEFORE_COMMIT,List.of(0L,1L));
    }

    @Test void kafkaCommitBeforePostgresMarkRepublishesDuplicateBeforeNextVersion() throws Exception {
        assertFencedSequence(PausePoint.AFTER_COMMIT,List.of(0L,0L,1L));
    }

    @Test void staleInitializationFencesCurrentProducerButFailsOwnershipRevalidationAndSendsNothing() throws Exception {
        assertFencedSequence(PausePoint.AFTER_ACQUIRE,List.of(0L,1L));
    }

    @Test void brokerRejectedPublicationLeavesImmutableOutboxBackedOffUntilSameEventPublishes() throws Exception {
        UUID caseId=createResolvedCase();
        StoredEvent before=stored(caseId,0);
        configureTopicValue(TopicConfig.MAX_MESSAGE_BYTES_CONFIG,"100");
        Map<String,Object> failing=new HashMap<>(springProducerFactory.getConfigurationProperties());
        failing.put(ProducerConfig.MAX_BLOCK_MS_CONFIG,2000);
        failing.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,500);
        failing.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,1500);
        failing.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG,100);
        relay(new KafkaProjectionTransactionalProducerFactory(failing,Duration.ofSeconds(3)),
                ProjectionPublicationObserver.NONE).publishDue();
        StoredEvent failed=stored(caseId,0);
        assertEquals(before,failed);
        assertEquals("PENDING",state(caseId,0));
        assertEquals(1,attempts(caseId,0));
        assertTrue(jdbc.sql("SELECT next_attempt_at>clock_timestamp() FROM fraud_case.fraud_case_projection_outbox WHERE fraud_case_id=:id AND aggregate_version=0")
                .param("id",caseId).query(Boolean.class).single());
        assertEquals(3,jdbc.sql("SELECT count(*) FROM fraud_case.fraud_case_projection_outbox WHERE fraud_case_id=:id")
                .param("id",caseId).query(Integer.class).single());

        configureTopicValue(TopicConfig.MAX_MESSAGE_BYTES_CONFIG,"1048588");
        jdbc.sql("UPDATE fraud_case.fraud_case_projection_outbox SET next_attempt_at=clock_timestamp() WHERE fraud_case_id=:id")
                .param("id",caseId).update();
        relay(realFactory(),ProjectionPublicationObserver.NONE).publishDue();
        assertEquals("PUBLISHED",state(caseId,0));
        assertEquals(before,stored(caseId,0));
        assertEquals(List.of(0L,1L,2L),consumeVersions(caseId,3));
    }

    @Test void genuineBrokerOutageAccumulatesPendingRowsAndFreshOwnerPublishesAfterSameEndpointReturns() throws Exception {
        CountDownLatch initialized=new CountDownLatch(1);
        CountDownLatch attemptDuringOutage=new CountDownLatch(1);
        ProjectionPublicationObserver observer=new ProjectionPublicationObserver(){
            @Override public void afterProducerInitialized(ProjectionPartitionOwner owner){
                initialized.countDown();
                try {
                    if(!attemptDuringOutage.await(20,TimeUnit.SECONDS))
                        throw new AssertionError("outage publication was not released");
                } catch(InterruptedException error){
                    Thread.currentThread().interrupt();throw new AssertionError(error);
                }
            }
        };
        Map<String,Object> bounded=new HashMap<>(springProducerFactory.getConfigurationProperties());
        bounded.put(ProducerConfig.MAX_BLOCK_MS_CONFIG,1500);
        bounded.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,500);
        bounded.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,2000);
        bounded.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG,100);
        FraudCaseProjectionProperties outageProperties=new FraudCaseProjectionProperties(
                properties.topic(),1,properties.batchSize(),properties.pollInterval(),Duration.ofSeconds(15),
                Duration.ofSeconds(1),properties.retryMaximumBackoff(),false,properties.bootstrapBatchSize(),
                properties.environment(),Duration.ofSeconds(3));
        var outageRelay=new FraudCaseProjectionRelay(store,
                new KafkaProjectionTransactionalProducerFactory(bounded,Duration.ofSeconds(3)),
                outageProperties,Clock.systemUTC(),observer);

        long outageStarted=0; long failureMillis; UUID caseId; List<StoredEvent> before;
        boolean paused=false;
        try(var executor=Executors.newSingleThreadExecutor()){
            var publication=executor.submit(outageRelay::publishDue);
            assertTrue(initialized.await(20,TimeUnit.SECONDS),"producer did not initialize before outage");
            KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();paused=true;
            outageStarted=System.nanoTime();
            assertBrokerUnreachable();

            caseId=createResolvedCase();
            assertEquals(2,jdbc.sql("SELECT count(*) FROM fraud_case.fraud_case_lifecycle_events WHERE fraud_case_id=:id")
                    .param("id",caseId).query(Integer.class).single());
            before=storedEvents(caseId);
            assertEquals(List.of(0L,1L,2L),before.stream().map(StoredEvent::version).toList());
            attemptDuringOutage.countDown();
            try {
                publication.get(12,TimeUnit.SECONDS);
            } catch(TimeoutException timeout){
                throw new AssertionError("outage publication exceeded 12 seconds; Kafka timeouts are ineffective",timeout);
            }
            failureMillis=Duration.ofNanos(System.nanoTime()-outageStarted).toMillis();
            assertTrue(failureMillis<12_000,"outage attempt was not bounded: "+failureMillis+"ms");
            assertEquals(List.of("PENDING","PENDING","PENDING"),states(caseId));
            assertEquals(1,attempts(caseId,0));
            assertTrue(jdbc.sql("SELECT next_attempt_at>clock_timestamp() FROM fraud_case.fraud_case_projection_outbox WHERE fraud_case_id=:id AND aggregate_version=0")
                    .param("id",caseId).query(Boolean.class).single());
            assertEquals(before,storedEvents(caseId));
            assertTrue(jdbc.sql("SELECT lease_token IS NULL AND lease_until IS NULL FROM fraud_case.fraud_case_projection_outbox WHERE fraud_case_id=:id AND aggregate_version=0")
                    .param("id",caseId).query(Boolean.class).single());
        } finally {
            attemptDuringOutage.countDown();
            if(paused)KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }

        long restoredAt=System.nanoTime();
        awaitBrokerRestored();
        jdbc.sql("UPDATE fraud_case.fraud_case_projection_outbox SET next_attempt_at=clock_timestamp() WHERE fraud_case_id=:id")
                .param("id",caseId).update();
        relay(realFactory(),ProjectionPublicationObserver.NONE).publishDue();
        long recoveryMillis=Duration.ofNanos(System.nanoTime()-restoredAt).toMillis();
        assertTrue(recoveryMillis<20_000,"recovery exceeded 20 seconds: "+recoveryMillis+"ms");
        assertEquals(before,storedEvents(caseId));
        assertEquals(List.of("PUBLISHED","PUBLISHED","PUBLISHED"),states(caseId));
        assertEquals(List.of(0L,1L,2L),consumeVersions(caseId,3));
        System.out.printf("TRUE_KAFKA_OUTAGE_TIMING failureMillis=%d recoveryMillis=%d%n",
                failureMillis,recoveryMillis);
    }

    private void assertFencedSequence(PausePoint point,List<Long> expected) throws Exception {
        UUID caseId=createClaimedCase();
        BlockingObserver blocker=new BlockingObserver(point);
        var relayA=relay(realFactory(),blocker);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var oldOwner=executor.submit(relayA::publishDue);
            assertTrue(blocker.reached.await(20,TimeUnit.SECONDS),"old relay did not reach "+point);
            expireOwnershipAndRowLease();
            relay(realFactory(),ProjectionPublicationObserver.NONE).publishDue();
            blocker.resume.countDown();
            oldOwner.get(20,TimeUnit.SECONDS);
        }
        List<Long> versions=consumeVersions(caseId,expected.size());
        assertEquals(expected,versions);
        assertFalse(hasDecrease(versions));
        assertNotEquals(List.of(0L,1L,0L),versions);
        assertEquals("test.case-projection-fencing.p0",properties.transactionalId(0));
    }

    private FraudCaseProjectionRelay relay(ProjectionTransactionalProducerFactory factory,
            ProjectionPublicationObserver observer){
        return new FraudCaseProjectionRelay(store,factory,properties,Clock.systemUTC(),observer);
    }

    private ProjectionTransactionalProducerFactory realFactory(){
        return new KafkaProjectionTransactionalProducerFactory(
                springProducerFactory.getConfigurationProperties(),Duration.ofSeconds(5));
    }

    private void configureTopicValue(String name,String value) throws Exception {
        try(AdminClient admin=AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,KAFKA.getBootstrapServers()))){
            ConfigResource topic=new ConfigResource(ConfigResource.Type.TOPIC,properties.topic());
            admin.incrementalAlterConfigs(Map.of(topic,List.of(new AlterConfigOp(
                    new ConfigEntry(name,value),AlterConfigOp.OpType.SET))))
                    .all().get(10,TimeUnit.SECONDS);
        }
    }

    private void assertBrokerUnreachable() throws Exception {
        Map<String,Object> configuration=Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,KAFKA.getBootstrapServers(),
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,500,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,1200);
        try(AdminClient admin=AdminClient.create(configuration)){
            assertThrows(Exception.class,()->admin.listTopics().names().get(2,TimeUnit.SECONDS));
        }
    }

    private void awaitBrokerRestored() throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        Exception last=null;
        while(System.nanoTime()<deadline){
            try(AdminClient admin=AdminClient.create(Map.of(
                    AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,KAFKA.getBootstrapServers(),
                    AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,500,
                    AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,1200))){
                if(admin.listTopics().names().get(2,TimeUnit.SECONDS).contains(properties.topic()))return;
            } catch(Exception failure){last=failure;}
            Thread.sleep(100);
        }
        throw new AssertionError("same Kafka broker endpoint did not recover within 20 seconds",last);
    }

    private UUID createClaimedCase(){
        UUID requestId=UUID.randomUUID();
        assertEquals(FraudCaseStore.CreationResult.CREATED,
                cases.create(parser.parse(reviewEvent(UUID.randomUUID(),requestId).build().toByteArray())));
        UUID caseId=jdbc.sql("SELECT case_id FROM fraud_case.fraud_cases WHERE request_id=:id")
                .param("id",requestId).query(UUID.class).single();
        lifecycle.claim(caseId,"analyst-a",0);
        return caseId;
    }

    private UUID createResolvedCase(){
        UUID caseId=createClaimedCase();
        lifecycle.resolve(caseId,"analyst-a",1,
                io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseResolutionOutcome.FALSE_POSITIVE,
                "Synthetic fencing rationale");
        return caseId;
    }

    private void expireOwnershipAndRowLease(){
        jdbc.sql("UPDATE fraud_case.projection_partition_ownership SET lease_until=clock_timestamp()-interval '1 second'").update();
        jdbc.sql("UPDATE fraud_case.fraud_case_projection_outbox SET lease_until=clock_timestamp()-interval '1 second' WHERE publication_state='IN_FLIGHT'").update();
    }

    private List<Long> consumeVersions(UUID caseId,int expected){
        Properties consumerProperties=new Properties();
        consumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,KAFKA.getBootstrapServers());
        consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG,"fencing-observer-"+UUID.randomUUID());
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");
        consumerProperties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG,"read_committed");
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,ByteArrayDeserializer.class);
        consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,ByteArrayDeserializer.class);
        List<Long> versions=new ArrayList<>();
        try(var consumer=new KafkaConsumer<byte[],byte[]>(consumerProperties)){
            consumer.subscribe(List.of(properties.topic()));
            long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            while(System.nanoTime()<deadline&&versions.size()<expected){
                for(var record:consumer.poll(Duration.ofMillis(200))){
                    if(new String(record.key(),StandardCharsets.UTF_8).equals(caseId.toString())) try {
                        versions.add(FraudCaseProjectionEvent.parseFrom(record.value()).getAggregateVersion());
                    } catch(Exception error){throw new RuntimeException(error);}
                }
            }
        }
        assertEquals(expected,versions.size(),"committed projection count");
        return versions;
    }

    private static boolean hasDecrease(List<Long> versions){
        for(int index=1;index<versions.size();index++) if(versions.get(index)<versions.get(index-1)) return true;
        return false;
    }

    private StoredEvent stored(UUID caseId,long version){return jdbc.sql("""
            SELECT event_id,aggregate_version,snapshot_hash,encode(payload,'hex') FROM fraud_case.fraud_case_projection_outbox
            WHERE fraud_case_id=:id AND aggregate_version=:version
            """).param("id",caseId).param("version",version)
                .query((rs,row)->new StoredEvent(rs.getObject(1,UUID.class),caseId.toString(),rs.getLong(2),rs.getString(3),rs.getString(4))).single();}
    private List<StoredEvent> storedEvents(UUID caseId){return jdbc.sql("""
            SELECT event_id,aggregate_version,snapshot_hash,encode(payload,'hex') FROM fraud_case.fraud_case_projection_outbox
            WHERE fraud_case_id=:id ORDER BY aggregate_version
            """).param("id",caseId).query((rs,row)->new StoredEvent(rs.getObject(1,UUID.class),caseId.toString(),
                    rs.getLong(2),rs.getString(3),rs.getString(4))).list();}
    private List<String> states(UUID caseId){return jdbc.sql("SELECT publication_state FROM fraud_case.fraud_case_projection_outbox WHERE fraud_case_id=:id ORDER BY aggregate_version")
            .param("id",caseId).query(String.class).list();}
    private String state(UUID caseId,long version){return jdbc.sql("SELECT publication_state FROM fraud_case.fraud_case_projection_outbox WHERE fraud_case_id=:id AND aggregate_version=:version")
            .param("id",caseId).param("version",version).query(String.class).single();}
    private int attempts(UUID caseId,long version){return jdbc.sql("SELECT attempt_count FROM fraud_case.fraud_case_projection_outbox WHERE fraud_case_id=:id AND aggregate_version=:version")
            .param("id",caseId).param("version",version).query(Integer.class).single();}
    private record StoredEvent(UUID eventId,String key,long version,String hash,String payloadHex){}

    private enum PausePoint { AFTER_ACQUIRE, BEFORE_SEND, BEFORE_COMMIT, AFTER_COMMIT }
    private static final class BlockingObserver implements ProjectionPublicationObserver {
        private final PausePoint point; private final CountDownLatch reached=new CountDownLatch(1);
        private final CountDownLatch resume=new CountDownLatch(1); private boolean paused;
        private BlockingObserver(PausePoint point){this.point=point;}
        public void afterOwnershipAcquired(ProjectionPartitionOwner owner){if(point==PausePoint.AFTER_ACQUIRE) pause();}
        public void beforeSend(ProjectionPartitionOwner owner,ClaimedProjectionEvent event){if(point==PausePoint.BEFORE_SEND) pause();}
        public void beforeCommit(ProjectionPartitionOwner owner,ClaimedProjectionEvent event){if(point==PausePoint.BEFORE_COMMIT) pause();}
        public void afterCommitBeforeMark(ProjectionPartitionOwner owner,ClaimedProjectionEvent event){if(point==PausePoint.AFTER_COMMIT) pause();}
        private synchronized void pause(){
            if(paused)return;paused=true;reached.countDown();
            try {if(!resume.await(30,TimeUnit.SECONDS))throw new AssertionError("publisher was not resumed");}
            catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}
        }
    }
}
