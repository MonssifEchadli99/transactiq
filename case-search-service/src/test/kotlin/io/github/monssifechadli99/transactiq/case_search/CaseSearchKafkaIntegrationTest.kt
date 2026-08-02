package io.github.monssifechadli99.transactiq.case_search

import com.google.protobuf.Timestamp
import io.github.monssifechadli99.transactiq.fraudcase.projection.v1.FraudCaseProjectionV1.*
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

@Testcontainers
@SpringBootTest(properties=["case-search.topic=search-projection-main","case-search.dlt-topic=search-projection-main.dlt",
    "case-search.retry-interval=500ms","case-search.retry-attempts=5","case-search.physical-index=search-kafka-v1",
    "case-search.opensearch-request-timeout=1s",
    "case-search.read-alias=search-kafka","case-search.write-alias=search-kafka-write"])
class CaseSearchKafkaIntegrationTest {
    companion object {
        @Container @JvmStatic val kafka=KafkaContainer(DockerImageName.parse("apache/kafka-native:4.1.0"))
        @Container @JvmStatic val openSearch=GenericContainer(DockerImageName.parse("opensearchproject/opensearch:3.2.0"))
            .withEnv("discovery.type","single-node").withEnv("DISABLE_SECURITY_PLUGIN","true")
            .withEnv("OPENSEARCH_JAVA_OPTS","-Xms512m -Xmx512m").withExposedPorts(9200)
        @DynamicPropertySource @JvmStatic fun properties(r:DynamicPropertyRegistry){
            r.add("spring.kafka.bootstrap-servers",kafka::getBootstrapServers)
            r.add("case-search.opensearch-url"){"http://${openSearch.host}:${openSearch.getMappedPort(9200)}"}
        }
    }
    @Autowired lateinit var template:KafkaTemplate<ByteArray,ByteArray>
    @Autowired lateinit var registry:KafkaListenerEndpointRegistry
    @Autowired lateinit var validator:ProjectionValidator
    private val rest by lazy { RestClient.builder().baseUrl("http://${openSearch.host}:${openSearch.getMappedPort(9200)}").build() }

    @Test fun `listener absorbs ordering routes integrity and invalid input and resumes after restart`(){
        val caseId=UUID.randomUUID().toString();val v0=event(caseId,0,"NEW",FraudCaseProjectionEventType.CREATED)
        send(caseId,v0);await{document(caseId)?.contains("\"aggregateVersion\":0")==true}
        send(caseId,v0);send(caseId,event(caseId,3,"RESOLVED",FraudCaseProjectionEventType.RESOLVED))
        send(caseId,event(caseId,1,"IN_REVIEW",FraudCaseProjectionEventType.CLAIMED))
        await{document(caseId)?.contains("\"aggregateVersion\":3")==true}
        val conflict=event(caseId,3,"RESOLVED",FraudCaseProjectionEventType.RESOLVED,"different rationale")
        send(caseId,conflict);assertNotNull(consumeDlt(caseId))
        template.send("search-projection-main","wrong-key".toByteArray(),v0.toByteArray()).get(10,TimeUnit.SECONDS)
        assertNotNull(consumeDlt("wrong-key"))
        registry.stop();registry.start()
        val next=UUID.randomUUID().toString();send(next,event(next,0,"NEW",FraudCaseProjectionEventType.CREATED))
        await{document(next)!=null}
        assertTrue(document(caseId)!!.contains("different rationale").not())
    }

    @Test fun `temporary OpenSearch outage retries and exhausted outage reaches DLT`(){
        val docker=openSearch.dockerClient;docker.pauseContainerCmd(openSearch.containerId).exec()
        try {
            val recovering=UUID.randomUUID().toString();send(recovering,event(recovering,0,"NEW",FraudCaseProjectionEventType.CREATED))
            Thread.sleep(200);docker.unpauseContainerCmd(openSearch.containerId).exec();await{document(recovering)!=null}
            docker.pauseContainerCmd(openSearch.containerId).exec()
            val exhausted=UUID.randomUUID().toString();send(exhausted,event(exhausted,0,"NEW",FraudCaseProjectionEventType.CREATED))
            assertNotNull(consumeDlt(exhausted,Duration.ofSeconds(15)))
        } finally { try{docker.unpauseContainerCmd(openSearch.containerId).exec()}catch(_:Exception){} }
    }
    private fun send(caseId:String,event:FraudCaseProjectionEvent)=template.send("search-projection-main",caseId.toByteArray(),event.toByteArray()).get(10,TimeUnit.SECONDS)
    private fun event(caseId:String,version:Long,status:String,type:FraudCaseProjectionEventType,rationale:String="synthetic rationale"):FraudCaseProjectionEvent{
        val time=Timestamp.newBuilder().setSeconds(1).build();val snapshot=FraudCaseProjectionSnapshot.newBuilder()
            .setCaseId(caseId).setRequestId(UUID.randomUUID().toString()).setStatus(status).setAggregateVersion(version)
            .setAuthorizationOccurredAt(time).setMerchantId("merchant-review").setMerchantCategoryCode("7995")
            .setAmount("125").setCurrency("EUR").setCountry("DE").setChannel("ECOMMERCE").setTransactionTime(time)
            .setNonFraudResult("PASSED").setAuthorizationDecision("APPROVED").setFraudAssessment("REVIEW")
            .setRiskScore(15).setCaseRequired(true).setCreatedAt(time).setUpdatedAt(time)
        if(status!="NEW")snapshot.assigneeId="analyst-a"
        if(status=="RESOLVED")snapshot.setResolutionOutcome("FALSE_POSITIVE").setResolutionRationale(rationale)
            .setResolvedBy("analyst-a").setResolvedAt(time)
        val built=snapshot.build();return FraudCaseProjectionEvent.newBuilder().setEventId(UUID.randomUUID().toString())
            .setEventType(type).setCaseId(caseId).setAggregateVersion(version).setOccurredAt(time)
            .setSnapshotHash(validator.hash(built)).setSnapshot(built).build()
    }
    private fun document(id:String):String?=try{rest.get().uri("/search-kafka/_doc/$id").retrieve().body(String::class.java)}
        catch(_:HttpClientErrorException.NotFound){null}
    private fun await(assertion:()->Boolean){val end=System.nanoTime()+Duration.ofSeconds(15).toNanos();while(System.nanoTime()<end){if(assertion())return;Thread.sleep(100)};fail<Unit>("condition not met within 15 seconds")}
    private fun consumeDlt(expectedKey:String,timeout:Duration=Duration.ofSeconds(8)):org.apache.kafka.clients.consumer.ConsumerRecord<ByteArray,ByteArray>?{
        val p=java.util.Properties();p[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG]=kafka.bootstrapServers;p[ConsumerConfig.GROUP_ID_CONFIG]="dlt-${UUID.randomUUID()}"
        p[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG]="earliest";p[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG]=ByteArrayDeserializer::class.java;p[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG]=ByteArrayDeserializer::class.java
        KafkaConsumer<ByteArray,ByteArray>(p).use{it.subscribe(listOf("search-projection-main.dlt"));val end=System.nanoTime()+timeout.toNanos();while(System.nanoTime()<end){for(record in it.poll(Duration.ofMillis(200)))if(record.key().toString(Charsets.UTF_8)==expectedKey)return record}}
        return null
    }
}
