package io.github.monssifechadli99.transactiq.simulator

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal fun testConfiguration(
    mode: ExecutionMode = ExecutionMode.SCENARIOS,
    scenario: ScenarioName? = null,
    runId: String = "test-run",
    seed: Long = 17,
    requestCount: Int = 10,
    concurrency: Int = 2,
    requestsPerSecond: java.math.BigDecimal? = null,
    baseUrl: URI = URI("http://localhost:8080"),
    connectTimeout: Duration = Duration.ofSeconds(1),
    requestTimeout: Duration = Duration.ofSeconds(2),
    fundedToken: String = "tok_A1B2C3D4",
    emptyToken: String = "tok_insufficient01",
): SimulatorConfiguration = SimulatorConfiguration(
    mode = mode,
    scenario = scenario,
    runId = runId,
    seed = seed,
    requestCount = requestCount,
    concurrency = concurrency,
    requestsPerSecond = requestsPerSecond,
    baseUrl = baseUrl,
    connectTimeout = connectTimeout,
    requestTimeout = requestTimeout,
    cards = SyntheticCards(
        SyntheticCard.validated("funded", fundedToken),
        SyntheticCard.validated("empty", emptyToken),
    ),
)

internal fun planned(configuration: SimulatorConfiguration): List<PlannedSubmission> = RequestPlanner(
    configuration,
    DeterministicClock.forConfiguration(configuration),
    SeededRandomSource(configuration.runId, configuration.seed),
).plan()

internal class RecordedRequest(
    val method: String,
    val path: String,
    val headers: Map<String, List<String>>,
    val body: String,
) {
    override fun toString(): String = "RecordedRequest(method=$method, path=$path, body=REDACTED)"
}

internal data class StubResponse(
    val status: Int,
    val body: String,
    val contentType: String = "application/json",
)

internal class InProcessAuthorizationServer(
    private val responder: (RecordedRequest, Int) -> StubResponse,
) : AutoCloseable {
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requests: MutableList<RecordedRequest> = CopyOnWriteArrayList()
    val baseUrl: URI

    init {
        server.executor = executor
        server.createContext(JdkAuthorizationGateway.AUTHORIZATION_PATH, ::handle)
        server.start()
        baseUrl = URI("http://127.0.0.1:${server.address.port}")
    }

    private fun handle(exchange: HttpExchange) {
        val request = RecordedRequest(
            method = exchange.requestMethod,
            path = exchange.requestURI.path,
            headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.toList() },
            body = exchange.requestBody.use { String(it.readAllBytes(), Charsets.UTF_8) },
        )
        val index = requests.size
        requests += request
        val response = responder(request, index)
        exchange.responseHeaders.add("Content-Type", response.contentType)
        val bytes = response.body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(response.status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}

internal object TestJson {
    private val mapper = JsonMapper.builder().build()

    fun parse(body: String): JsonNode = mapper.readTree(body)

    fun requestId(body: String): String = parse(body).get("requestId").stringValue()

    fun rawAmount(body: String): String = requireNotNull(
        Regex("\\\"amount\\\":([^,}]+)").find(body)?.groupValues?.get(1),
    )
}

internal fun approvedBody(request: RecordedRequest): String =
    "{\"requestId\":\"${TestJson.requestId(request.body)}\",\"decision\":\"APPROVED\"}"

internal fun declinedBody(request: RecordedRequest, reason: String): String =
    "{\"requestId\":\"${TestJson.requestId(request.body)}\",\"decision\":\"DECLINED\",\"declineReason\":\"$reason\"}"
