package io.github.monssifechadli99.transactiq.case_management.projection;

import static io.github.monssifechadli99.transactiq.case_management.support.AuthorizationEventFixtures.reviewEvent;
import static org.junit.jupiter.api.Assertions.*;
import io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka.AuthorizationCompletedEventParser;
import io.github.monssifechadli99.transactiq.case_management.application.port.out.FraudCaseStore;
import io.github.monssifechadli99.transactiq.case_management.adapter.out.jdbc.JdbcFraudCaseLifecycleStore;
import io.github.monssifechadli99.transactiq.case_management.domain.FraudCaseResolutionOutcome;
import io.github.monssifechadli99.transactiq.case_management.configuration.FraudCaseProjectionProperties;
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.FraudCaseProjectionEvent;
import io.github.monssifechadli99.transactiq.case_search.CaseSearchProperties;
import io.github.monssifechadli99.transactiq.case_search.OpenSearchIndexInitializer;
import io.github.monssifechadli99.transactiq.case_search.OpenSearchProjectionStore;
import io.github.monssifechadli99.transactiq.case_search.ProjectionDocumentMapper;
import io.github.monssifechadli99.transactiq.case_search.ProjectionValidator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(properties={"spring.kafka.listener.auto-startup=false",
        "fraud-case.projection.topic=case-projection-pipeline","fraud-case.projection.poll-interval=1h"})
class FraudCaseProjectionPipelineIntegrationTest {
    @Container static final KafkaContainer KAFKA=new KafkaContainer(DockerImageName.parse("apache/kafka-native:4.1.0"));
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("postgres:18.4-alpine3.24"))
            .withDatabaseName("transactiq_case_management").withUsername("case_test").withPassword("case_test");
    @Container static final GenericContainer<?> OPENSEARCH=new GenericContainer<>(DockerImageName.parse("opensearchproject/opensearch:3.2.0"))
            .withEnv("discovery.type","single-node").withEnv("DISABLE_SECURITY_PLUGIN","true")
            .withEnv("OPENSEARCH_JAVA_OPTS","-Xms512m -Xmx512m").withExposedPorts(9200);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.kafka.bootstrap-servers",KAFKA::getBootstrapServers);r.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired FraudCaseStore cases; @Autowired FraudCaseProjectionRelay relay; @Autowired JdbcClient jdbc;
    @Autowired JdbcFraudCaseLifecycleStore lifecycle; @Autowired FraudCaseProjectionOutbox outbox;
    @Autowired JdbcProjectionDeliveryStore deliveryStore; @Autowired PlatformTransactionManager transactionManager;
    @Autowired ProjectionTransactionalProducerFactory producerFactory; @Autowired FraudCaseProjectionProperties projectionProperties;
    private final AuthorizationCompletedEventParser parser=new AuthorizationCompletedEventParser();
    @BeforeEach void reset(){jdbc.sql("TRUNCATE fraud_case.fraud_cases CASCADE").update();
        jdbc.sql("TRUNCATE fraud_case.projection_partition_ownership").update();}
    @Test void postgresOutboxPublishesStableKeyBytesAndEventIdentityToCompactedTopic() throws Exception {
        UUID requestId=UUID.randomUUID();
        assertEquals(FraudCaseStore.CreationResult.CREATED,cases.create(parser.parse(reviewEvent(UUID.randomUUID(),requestId).build().toByteArray())));
        byte[] stored=jdbc.sql("SELECT payload FROM fraud_case.fraud_case_projection_outbox").query(byte[].class).single();
        relay.publishDue();
        assertEquals("PUBLISHED",jdbc.sql("SELECT publication_state FROM fraud_case.fraud_case_projection_outbox").query(String.class).single());
        jdbc.sql("UPDATE fraud_case.fraud_case_projection_outbox SET publication_state='PENDING',published_at=NULL,next_attempt_at=now()") .update();
        relay.publishDue();var records=consumeTwo();var first=records.get(0);var duplicate=records.get(1);
        var event=FraudCaseProjectionEvent.parseFrom(first.value());
        assertArrayEquals(stored,first.value());assertEquals(event.getCaseId(),new String(first.key(),StandardCharsets.UTF_8));
        assertArrayEquals(first.key(),duplicate.key());assertArrayEquals(first.value(),duplicate.value());
        assertEquals(event.getEventId(),FraudCaseProjectionEvent.parseFrom(duplicate.value()).getEventId());
        String url="http://"+OPENSEARCH.getHost()+":"+OPENSEARCH.getMappedPort(9200);
        var searchProperties=new CaseSearchProperties("case-projection-pipeline","case-projection-pipeline.dlt",1,
                Duration.ofMillis(10),2,url,Duration.ofSeconds(2),"transactiq-fraud-cases-v1","transactiq-fraud-cases","transactiq-fraud-cases-write");
        var client=RestClient.builder().baseUrl(url).build();var json=JsonMapper.builder().build();
        new OpenSearchIndexInitializer(client,searchProperties,json).afterPropertiesSet();
        var validator=new ProjectionValidator();var snapshot=validator.validate(first.key(),event);
        new OpenSearchProjectionStore(client,json,searchProperties).apply(event.getCaseId(),new ProjectionDocumentMapper().map(event,snapshot));
        String indexed=client.get().uri("/transactiq-fraud-cases/_doc/"+event.getCaseId()).retrieve().body(String.class);
        assertTrue(indexed.contains(event.getCaseId()));assertFalse(indexed.contains("cardTokenFingerprint"));
        try(AdminClient admin=AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,KAFKA.getBootstrapServers()))){
            var resource=new ConfigResource(ConfigResource.Type.TOPIC,"case-projection-pipeline");
            Config config=admin.describeConfigs(java.util.List.of(resource)).all().get(10,TimeUnit.SECONDS).get(resource);
            assertEquals(TopicConfig.CLEANUP_POLICY_COMPACT,config.get(TopicConfig.CLEANUP_POLICY_CONFIG).value());
        }
    }
    @Test void explicitBootstrapMaterializesEveryCurrentStatusAndRerunSkips(){
        UUID fresh=createCase();UUID claimed=createCase();UUID resolved=createCase();
        lifecycle.claim(claimed,"analyst-a",0);lifecycle.claim(resolved,"analyst-a",0);
        lifecycle.resolve(resolved,"analyst-a",1,FraudCaseResolutionOutcome.FALSE_POSITIVE,"Synthetic bootstrap rationale");
        jdbc.sql("DELETE FROM fraud_case.fraud_case_projection_outbox").update();
        var bootstrap=new FraudCaseProjectionBootstrap(jdbc,lifecycle,outbox,2);
        assertEquals(new FraudCaseProjectionBootstrap.Result(3,0,0),bootstrap.run());
        assertEquals(java.util.Set.of("CREATED","CLAIMED","RESOLVED"),new java.util.HashSet<>(jdbc.sql(
                "SELECT event_type FROM fraud_case.fraud_case_projection_outbox").query(String.class).list()));
        assertEquals(new FraudCaseProjectionBootstrap.Result(0,3,0),bootstrap.run());
    }
    @Test void bootstrapSameVersionDifferentHashStopsWithoutChangingCaseOrExistingOutbox(){
        UUID caseId=createCase();
        var caseBefore=lifecycle.findById(caseId).orElseThrow();
        var historyBefore=lifecycle.findHistory(caseId).orElseThrow();
        jdbc.sql("ALTER TABLE fraud_case.fraud_case_projection_outbox DISABLE TRIGGER fraud_case_projection_outbox_immutable").update();
        try {
            jdbc.sql("UPDATE fraud_case.fraud_case_projection_outbox SET snapshot_hash=:hash WHERE fraud_case_id=:id")
                    .param("hash","0".repeat(64)).param("id",caseId).update();
        } finally {
            jdbc.sql("ALTER TABLE fraud_case.fraud_case_projection_outbox ENABLE TRIGGER fraud_case_projection_outbox_immutable").update();
        }
        var rowBefore=projectionRow(caseId);
        var bootstrap=new FraudCaseProjectionBootstrap(jdbc,lifecycle,outbox,10);
        assertThrows(ProjectionIntegrityException.class,bootstrap::run);
        assertEquals(rowBefore,projectionRow(caseId));
        assertEquals(1,jdbc.sql("SELECT count(*) FROM fraud_case.fraud_case_projection_outbox WHERE fraud_case_id=:id")
                .param("id",caseId).query(Integer.class).single());
        assertEquals(caseBefore,lifecycle.findById(caseId).orElseThrow());
        assertEquals(historyBefore,lifecycle.findHistory(caseId).orElseThrow());
    }
    @Test void perCaseHeadOfLineBlocksConcurrentVersionsButAllowsDifferentCases(){
        UUID caseId=createCase();lifecycle.claim(caseId,"analyst-a",0);
        var otherInstance=new JdbcProjectionDeliveryStore(jdbc,new TransactionTemplate(transactionManager));
        Instant now=Instant.now();var owner=deliveryStore.acquire("case-projection-pipeline",0,Duration.ofSeconds(30));
        var first=deliveryStore.claim(owner,10,now,Duration.ofSeconds(30));
        assertEquals(1,first.size());assertEquals(0,first.getFirst().aggregateVersion());
        assertTrue(otherInstance.claim(owner,10,now,Duration.ofSeconds(30)).isEmpty());
        assertTrue(deliveryStore.published(owner,first.getFirst(),now));
        var second=otherInstance.claim(owner,10,now,Duration.ofSeconds(30));
        assertEquals(1,second.size());assertEquals(1,second.getFirst().aggregateVersion());

        jdbc.sql("TRUNCATE fraud_case.fraud_cases CASCADE").update();createCase();createCase();
        var differentCases=deliveryStore.claim(owner,10,Instant.now(),Duration.ofSeconds(30));
        assertEquals(2,differentCases.size());
        assertNotEquals(differentCases.get(0).caseId(),differentCases.get(1).caseId());
    }
    @Test void crashWindowRepublishesLowerVersionBeforeHigherAndRebuildEndsAtHighest(){
        UUID caseId=createCase();lifecycle.claim(caseId,"analyst-a",0);
        var crashRelay=new FraudCaseProjectionRelay(deliveryStore,producerFactory,projectionProperties,
                java.time.Clock.systemUTC(),new ProjectionPublicationObserver(){
                    private boolean crashed;
                    @Override public void afterCommitBeforeMark(ProjectionPartitionOwner owner,ClaimedProjectionEvent event){
                        if(!crashed){crashed=true;throw new IllegalStateException("synthetic crash before PostgreSQL mark");}
                    }
                });
        crashRelay.publishDue();
        jdbc.sql("UPDATE fraud_case.fraud_case_projection_outbox SET next_attempt_at=now() WHERE fraud_case_id=:id AND aggregate_version=0")
                .param("id",caseId).update();
        relay.publishDue();
        var records=consumeForKey(caseId.toString(),3);
        var versions=records.stream().map(record->{try{return FraudCaseProjectionEvent.parseFrom(record.value()).getAggregateVersion();}
            catch(Exception error){throw new RuntimeException(error);}}).toList();
        assertEquals(java.util.List.of(0L,0L,1L),versions);
        assertTrue(records.get(0).offset()<records.get(1).offset()&&records.get(1).offset()<records.get(2).offset());
        assertEquals(1L,versions.getLast());
    }
    private UUID createCase(){UUID request=UUID.randomUUID();cases.create(parser.parse(reviewEvent(UUID.randomUUID(),request).build().toByteArray()));
        return jdbc.sql("SELECT case_id FROM fraud_case.fraud_cases WHERE request_id=:id").param("id",request).query(UUID.class).single();}
    private ProjectionRow projectionRow(UUID caseId){return jdbc.sql("""
            SELECT event_id,event_type,snapshot_hash,payload,aggregate_version,occurred_at,created_at
            FROM fraud_case.fraud_case_projection_outbox WHERE fraud_case_id=:id
            """).param("id",caseId).query((rs,row)->new ProjectionRow(rs.getObject(1,UUID.class),rs.getString(2),
                    rs.getString(3),java.util.HexFormat.of().formatHex(rs.getBytes(4)),rs.getLong(5),
                    rs.getObject(6,java.time.OffsetDateTime.class),rs.getObject(7,java.time.OffsetDateTime.class))).single();}
    private record ProjectionRow(UUID eventId,String type,String hash,String payloadHex,long version,
            java.time.OffsetDateTime occurredAt,java.time.OffsetDateTime createdAt){}
    private java.util.List<org.apache.kafka.clients.consumer.ConsumerRecord<byte[],byte[]>> consumeTwo(){
        var props=new java.util.Properties();props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG,"projection-observer-"+UUID.randomUUID());props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG,"read_committed");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,ByteArrayDeserializer.class);props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,ByteArrayDeserializer.class);
        try(var consumer=new KafkaConsumer<byte[],byte[]>(props)){consumer.subscribe(java.util.List.of("case-projection-pipeline"));
            var found=new java.util.ArrayList<org.apache.kafka.clients.consumer.ConsumerRecord<byte[],byte[]>>();
            long end=System.nanoTime()+Duration.ofSeconds(20).toNanos();while(System.nanoTime()<end){consumer.poll(Duration.ofMillis(250)).forEach(found::add);if(found.size()>=2)return found;}}
        throw new AssertionError("two projection records not received within 20 seconds");
    }
    private java.util.List<org.apache.kafka.clients.consumer.ConsumerRecord<byte[],byte[]>> consumeForKey(String key,int count){
        var props=new java.util.Properties();props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG,"projection-rebuild-"+UUID.randomUUID());props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG,"read_committed");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,ByteArrayDeserializer.class);props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,ByteArrayDeserializer.class);
        try(var consumer=new KafkaConsumer<byte[],byte[]>(props)){consumer.subscribe(java.util.List.of("case-projection-pipeline"));
            var found=new java.util.ArrayList<org.apache.kafka.clients.consumer.ConsumerRecord<byte[],byte[]>>();long end=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            while(System.nanoTime()<end){for(var record:consumer.poll(Duration.ofMillis(250)))if(new String(record.key(),StandardCharsets.UTF_8).equals(key))found.add(record);if(found.size()>=count)return found;}}
        throw new AssertionError("ordered projection records not received within 20 seconds");
    }
}
