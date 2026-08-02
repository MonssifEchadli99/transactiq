package io.github.monssifechadli99.transactiq.case_search

import java.time.Duration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.ObjectMapper

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties=[
    "spring.kafka.listener.auto-startup=false","case-search.physical-index=search-api-v1",
    "case-search.read-alias=search-api","case-search.write-alias=search-api-write",
    "case-search.opensearch-request-timeout=2s"])
class FraudCaseSearchApiIntegrationTest {
    companion object {
        @JvmField val openSearch=GenericContainer(DockerImageName.parse("opensearchproject/opensearch:3.2.0"))
            .withEnv("discovery.type","single-node").withEnv("DISABLE_SECURITY_PLUGIN","true")
            .withEnv("OPENSEARCH_JAVA_OPTS","-Xms512m -Xmx512m").withExposedPorts(9200).also{it.start()}
        @DynamicPropertySource @JvmStatic fun properties(registry:DynamicPropertyRegistry)=
            registry.add("case-search.opensearch-url"){"http://${openSearch.host}:${openSearch.getMappedPort(9200)}"}
    }
    @LocalServerPort var port:Int=0
    private lateinit var api:RestClient
    private lateinit var os:RestClient
    private lateinit var mapper:ObjectMapper

    @BeforeAll fun seed(){
        api=RestClient.builder().baseUrl("http://localhost:$port").build()
        os=RestClient.builder().baseUrl("http://${openSearch.host}:${openSearch.getMappedPort(9200)}").build()
        mapper=tools.jackson.databind.json.JsonMapper.builder().build()
        index(document("00000000-0000-0000-0000-000000000601","NEW","REVIEW",35,"2026-08-01T10:00:00Z","merchant-alpha",
            mapOf("matchedRules" to listOf(mapOf("ruleCode" to "VELOCITY","severity" to "REVIEW","evidence" to "rapid synthetic purchases","scoreContribution" to 35)))))
        index(document("00000000-0000-0000-0000-000000000602","IN_REVIEW","HIGH_RISK",90,"2026-08-01T10:00:00Z","merchant-beta",
            mapOf("assigneeId" to "analyst-a","matchedRules" to emptyList<Map<String,Any>>())))
        index(document("00000000-0000-0000-0000-000000000603","RESOLVED","HIGH_RISK",80,"2026-08-02T10:00:00Z","merchant-gamma",
            mapOf("assigneeId" to "analyst-a","resolutionOutcome" to "CONFIRMED_FRAUD","resolutionRationale" to "synthetic account takeover",
                "resolvedBy" to "analyst-a","snapshotHash" to "sensitive-hash","matchedRules" to emptyList<Map<String,Any>>())))
    }
    @AfterAll fun stopContainer(){openSearch.stop()}

    @Test @Order(1) fun `free text filters deterministic cursor traversal empty and safe summaries`() {
        val text=get("/api/v1/fraud-cases/search?q=rapid")
        assertEquals(listOf("00000000-0000-0000-0000-000000000601"),ids(text))
        val filtered=get("/api/v1/fraud-cases/search?fraudAssessment=HIGH_RISK&status=IN_REVIEW")
        assertEquals(listOf("00000000-0000-0000-0000-000000000602"),ids(filtered))

        val first=get("/api/v1/fraud-cases/search?sort=CREATED_AT_ASC&pageSize=1")
        val cursor=read(first)["nextCursor"].asText();val second=get("/api/v1/fraud-cases/search?sort=CREATED_AT_ASC&pageSize=1&cursor=$cursor")
        val thirdCursor=read(second)["nextCursor"].asText();val third=get("/api/v1/fraud-cases/search?sort=CREATED_AT_ASC&pageSize=1&cursor=$thirdCursor")
        val traversed=ids(first)+ids(second)+ids(third)
        assertEquals(listOf("00000000-0000-0000-0000-000000000601","00000000-0000-0000-0000-000000000602",
            "00000000-0000-0000-0000-000000000603"),traversed)
        assertEquals(traversed.size,traversed.toSet().size)
        assertTrue(ids(get("/api/v1/fraud-cases/search?q=no-such-synthetic-case")).isEmpty())
        assertFalse(third.contains("snapshotHash"));assertFalse(third.contains("resolutionRationale"))
        assertFalse(third.contains("matchedRules"));assertFalse(third.contains("resolvedBy"));assertFalse(third.contains("requestId"))
    }

    @Test @Order(2) fun `invalid query cursor filter and sort return bad request`() {
        listOf("?q=","?cursor=broken","?status=UNKNOWN","?sort=RISK_DESC","?pageSize=101").forEach { query ->
            val error=assertThrows(HttpClientErrorException.BadRequest::class.java){get("/api/v1/fraud-cases/search$query")}
            assertEquals(400,error.statusCode.value())
        }
    }

    @Test @Order(3) fun `OpenSearch outage returns service unavailable`() {
        openSearch.dockerClient.pauseContainerCmd(openSearch.containerId).exec()
        try { val error=assertThrows(HttpServerErrorException.ServiceUnavailable::class.java){get("/api/v1/fraud-cases/search")}
            assertEquals(503,error.statusCode.value())
        } finally { openSearch.dockerClient.unpauseContainerCmd(openSearch.containerId).exec() }
    }

    private fun get(uri:String)=api.get().uri(uri).retrieve().body(String::class.java).orEmpty()
    private fun read(json:String)=mapper.readTree(json)
    private fun ids(json:String):List<String> {
        val items=read(json)["items"]
        return (0 until items.size()).map{items[it]["caseId"].stringValue()}
    }
    private fun index(document:Map<String,Any>) { os.put().uri("/search-api-write/_doc/${document["caseId"]}?refresh=wait_for")
        .contentType(MediaType.APPLICATION_JSON).body(document).retrieve().toBodilessEntity() }
    private fun document(id:String,status:String,assessment:String,risk:Int,created:String,merchant:String,extra:Map<String,Any>)=
        mapOf<String,Any>("caseId" to id,"requestId" to "request-$id","status" to status,"aggregateVersion" to 1,
            "snapshotHash" to "hash-$id","authorizationOccurredAt" to created,"merchantId" to merchant,
            "merchantCategoryCode" to "7995","amount" to "125.00","currency" to "EUR","country" to "DE",
            "channel" to "ECOMMERCE","transactionTime" to created,"nonFraudResult" to "PASSED",
            "authorizationDecision" to "APPROVED","fraudAssessment" to assessment,"riskScore" to risk,
            "caseRequired" to true,"createdAt" to created,"updatedAt" to created)+extra
}
