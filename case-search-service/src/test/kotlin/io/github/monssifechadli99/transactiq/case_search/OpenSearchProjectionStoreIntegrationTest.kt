package io.github.monssifechadli99.transactiq.case_search

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenSearchProjectionStoreIntegrationTest {
    companion object {
        @Container @JvmStatic val openSearch=GenericContainer(DockerImageName.parse("opensearchproject/opensearch:3.2.0"))
            .withEnv("discovery.type","single-node").withEnv("DISABLE_SECURITY_PLUGIN","true")
            .withEnv("OPENSEARCH_JAVA_OPTS","-Xms512m -Xmx512m").withExposedPorts(9200)
    }
    private lateinit var client:RestClient; private lateinit var store:OpenSearchProjectionStore
    private val mapper=JsonMapper.builder().build()
    @BeforeAll fun initialize(){
        client=RestClient.builder().baseUrl("http://${openSearch.host}:${openSearch.getMappedPort(9200)}").build()
        val p=CaseSearchProperties("main","dlt",1,java.time.Duration.ofMillis(10),2,
            "http://${openSearch.host}:${openSearch.getMappedPort(9200)}",java.time.Duration.ofSeconds(2),"transactiq-fraud-cases-v1",
            "transactiq-fraud-cases","transactiq-fraud-cases-write")
        OpenSearchIndexInitializer(client,p,mapper).afterPropertiesSet();store=OpenSearchProjectionStore(client,mapper,p)
        OpenSearchIndexInitializer(client,p,mapper).afterPropertiesSet()
    }
    @Test fun `versions are monotonic duplicates are harmless and gaps replace complete document`(){
        val id="00000000-0000-0000-0000-000000000501"
        store.apply(id,document(id,0,"hash-0",mapOf("assigneeId" to "old")))
        store.apply(id,document(id,0,"hash-0",mapOf("assigneeId" to "old")))
        store.apply(id,document(id,3,"hash-3"))
        store.apply(id,document(id,1,"hash-1",mapOf("assigneeId" to "stale")))
        val source=source(id)
        assertEquals(3,(source["aggregateVersion"] as Number).toInt())
        assertEquals("hash-3",source["snapshotHash"])
        assertFalse(source.containsKey("assigneeId"))
    }
    @Test fun `same version different hash preserves document and raises integrity conflict`(){
        val id="00000000-0000-0000-0000-000000000502"
        store.apply(id,document(id,1,"original"))
        assertThrows(ProjectionIntegrityException::class.java){store.apply(id,document(id,1,"different"))}
        assertEquals("original",source(id)["snapshotHash"])
    }
    @Test fun `strict mapping rejects undeclared fields`(){
        val error=assertThrows(HttpClientErrorException::class.java){client.put().uri("/transactiq-fraud-cases-write/_doc/strict")
            .body(mapOf("caseId" to "strict","undeclared" to "forbidden")).retrieve().toBodilessEntity()}
        assertTrue(error.statusCode.is4xxClientError)
    }
    @Test fun `initializer rejects incompatible declared mapping and every incompatible alias topology`(){
        val incompatible=properties("bad-field")
        client.put().uri("/${incompatible.physicalIndex}").contentType(MediaType.APPLICATION_JSON)
            .body("""{"mappings":{"dynamic":"strict","properties":{"caseId":{"type":"text"}}}}""")
            .retrieve().toBodilessEntity()
        assertThrows(IllegalArgumentException::class.java){OpenSearchIndexInitializer(client,incompatible,mapper).afterPropertiesSet()}

        val missing=properties("missing-write");createExpectedIndex(missing)
        addAliases("""{"actions":[{"add":{"index":"${missing.physicalIndex}","alias":"${missing.readAlias}"}}]}""")
        assertThrows(IllegalStateException::class.java){OpenSearchIndexInitializer(client,missing,mapper).afterPropertiesSet()}

        val wrong=properties("wrong-target");createExpectedIndex(wrong);client.put().uri("/${wrong.physicalIndex}-other").retrieve().toBodilessEntity()
        addAliases("""{"actions":[{"add":{"index":"${wrong.physicalIndex}-other","alias":"${wrong.readAlias}"}},
          {"add":{"index":"${wrong.physicalIndex}-other","alias":"${wrong.writeAlias}","is_write_index":true}}]}""")
        assertThrows(IllegalArgumentException::class.java){OpenSearchIndexInitializer(client,wrong,mapper).afterPropertiesSet()}

        val noWrite=properties("no-write-designation");createExpectedIndex(noWrite)
        addAliases("""{"actions":[{"add":{"index":"${noWrite.physicalIndex}","alias":"${noWrite.readAlias}"}},
          {"add":{"index":"${noWrite.physicalIndex}","alias":"${noWrite.writeAlias}"}}]}""")
        assertThrows(IllegalArgumentException::class.java){OpenSearchIndexInitializer(client,noWrite,mapper).afterPropertiesSet()}

        val multiple=properties("multiple-targets");createExpectedIndex(multiple);client.put().uri("/${multiple.physicalIndex}-other").retrieve().toBodilessEntity()
        addAliases("""{"actions":[{"add":{"index":"${multiple.physicalIndex}","alias":"${multiple.readAlias}"}},
          {"add":{"index":"${multiple.physicalIndex}-other","alias":"${multiple.readAlias}"}},
          {"add":{"index":"${multiple.physicalIndex}","alias":"${multiple.writeAlias}","is_write_index":true}}]}""")
        assertThrows(IllegalArgumentException::class.java){OpenSearchIndexInitializer(client,multiple,mapper).afterPropertiesSet()}
    }
    private fun properties(suffix:String)=CaseSearchProperties("main","dlt",1,java.time.Duration.ofMillis(10),2,
        "http://${openSearch.host}:${openSearch.getMappedPort(9200)}",java.time.Duration.ofSeconds(2),
        "fraud-cases-$suffix","fraud-cases-read-$suffix","fraud-cases-write-$suffix")
    private fun createExpectedIndex(p:CaseSearchProperties){
        val mapping=ClassPathResource("opensearch/fraud-cases-v1.json").inputStream.bufferedReader().use{it.readText()}
        client.put().uri("/${p.physicalIndex}").contentType(MediaType.APPLICATION_JSON).body(mapping).retrieve().toBodilessEntity()
    }
    private fun addAliases(body:String){client.post().uri("/_aliases").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity()}
    private fun document(id:String,version:Long,hash:String,extra:Map<String,Any> = emptyMap())=
        mapOf<String,Any>("caseId" to id,"requestId" to "request-$id","status" to "NEW",
            "aggregateVersion" to version,"snapshotHash" to hash,"authorizationOccurredAt" to "2026-08-01T10:00:00Z",
            "merchantId" to "merchant-review","merchantCategoryCode" to "7995","amount" to "125.00",
            "currency" to "EUR","country" to "DE","channel" to "ECOMMERCE","transactionTime" to "2026-08-01T10:00:00Z",
            "nonFraudResult" to "PASSED","authorizationDecision" to "APPROVED","fraudAssessment" to "REVIEW",
            "riskScore" to 25,"caseRequired" to true,"createdAt" to "2026-08-01T10:00:00Z",
            "updatedAt" to "2026-08-01T10:00:00Z","matchedRules" to emptyList<Map<String,Any>>())+extra
    @Suppress("UNCHECKED_CAST") private fun source(id:String):Map<String,Any>{
        val json=client.get().uri("/transactiq-fraud-cases/_doc/$id").retrieve().body(String::class.java)!!
        return mapper.readValue(json,Map::class.java)["_source"] as Map<String,Any>
    }
}
