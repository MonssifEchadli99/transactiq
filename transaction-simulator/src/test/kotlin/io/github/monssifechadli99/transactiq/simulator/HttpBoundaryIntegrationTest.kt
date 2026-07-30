package io.github.monssifechadli99.transactiq.simulator

import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpBoundaryIntegrationTest {
    @Test
    fun `jdk client submits exact public request contract and parses approval`() {
        InProcessAuthorizationServer { request, _ -> StubResponse(200, approvedBody(request)) }.use { server ->
            val configuration = testConfiguration(
                mode = ExecutionMode.SINGLE,
                scenario = ScenarioName.CLEAR,
                baseUrl = server.baseUrl,
            )
            val plan = planned(configuration).single()

            val result = submit(configuration, plan)

            val completed = assertIs<SubmissionResult.Completed>(result)
            val response = assertIs<ParsedAuthorizationResponse.Decision>(completed.response)
            assertEquals("APPROVED", response.decision)
            assertNull(response.declineReason)
            assertEquals(plan.request.requestId, response.requestId)

            val recorded = server.requests.single()
            val json = TestJson.parse(recorded.body)
            assertEquals("POST", recorded.method)
            assertEquals("/api/v1/authorizations", recorded.path)
            assertTrue(recorded.headers.getValue("content-type").any { it.startsWith("application/json") })
            assertTrue(recorded.headers.getValue("accept").any { it == "application/json" })
            assertEquals(plan.request.requestId.toString(), json.get("requestId").stringValue())
            assertEquals(plan.request.card.fingerprint, sha256(json.get("cardToken").stringValue()))
            assertEquals(plan.request.merchantId, json.get("merchantId").stringValue())
            assertEquals(plan.request.merchantCategoryCode, json.get("merchantCategoryCode").stringValue())
            assertEquals(plan.request.amount, TestJson.rawAmount(recorded.body))
            assertEquals(plan.request.currency, json.get("currency").stringValue())
            assertEquals(plan.request.country, json.get("country").stringValue())
            assertEquals(plan.request.channel.name, json.get("channel").stringValue())
            assertEquals(plan.request.transactionTime.toString(), json.get("transactionTime").stringValue())
            assertEquals(9, json.size())
        }
    }

    @Test
    fun `declined response exposes only the public decision and reason`() {
        InProcessAuthorizationServer { request, _ ->
            StubResponse(200, declinedBody(request, "HIGH_FRAUD_RISK"))
        }.use { server ->
            val configuration = testConfiguration(
                mode = ExecutionMode.SINGLE,
                scenario = ScenarioName.HIGH_RISK,
                baseUrl = server.baseUrl,
            )

            val completed = assertIs<SubmissionResult.Completed>(submit(configuration, planned(configuration).single()))
            val response = assertIs<ParsedAuthorizationResponse.Decision>(completed.response)

            assertEquals("DECLINED", response.decision)
            assertEquals("HIGH_FRAUD_RISK", response.declineReason)
        }
    }

    @Test
    fun `business 400 and conflict 409 are parsed while technical 500 fails`() {
        val responses = listOf(
            StubResponse(400, "{\"code\":\"UNKNOWN_CARD_TOKEN\"}"),
            StubResponse(409, "{\"code\":\"REQUEST_ID_CONFLICT\"}"),
            StubResponse(500, "{\"code\":\"AUTHORIZATION_PROCESSING_ERROR\"}"),
        )
        InProcessAuthorizationServer { _, index -> responses[index] }.use { server ->
            val configuration = testConfiguration(
                mode = ExecutionMode.SINGLE,
                scenario = ScenarioName.CLEAR,
                baseUrl = server.baseUrl,
            )
            val plan = planned(configuration).single()
            JdkAuthorizationGateway(
                server.baseUrl,
                configuration.connectTimeout,
                configuration.requestTimeout,
            ).use { gateway ->
                val results = runBlocking { responses.indices.map { gateway.submit(plan) } }

                assertEquals("UNKNOWN_CARD_TOKEN", errorCode(results[0]))
                assertEquals("REQUEST_ID_CONFLICT", errorCode(results[1]))
                val technical = assertIs<SubmissionResult.Failed>(results[2])
                assertEquals(500, technical.httpStatus)
                assertEquals("HTTP_TECHNICAL_FAILURE", technical.failureCode)
            }
        }
    }

    @Test
    fun `malformed responses fail without exposing their body`() {
        val rawToken = "tok_ResponseSecret55"
        InProcessAuthorizationServer { _, _ -> StubResponse(200, "unexpected-$rawToken") }.use { server ->
            val configuration = testConfiguration(
                mode = ExecutionMode.SINGLE,
                scenario = ScenarioName.CLEAR,
                baseUrl = server.baseUrl,
                fundedToken = rawToken,
            )

            val failed = assertIs<SubmissionResult.Failed>(submit(configuration, planned(configuration).single()))

            assertEquals("MALFORMED_RESPONSE", failed.failureCode)
            assertEquals(200, failed.httpStatus)
            assertFalse(failed.toString().contains(rawToken))
        }
    }

    @Test
    fun `request timeout is classified and does not retry`() {
        val release = CountDownLatch(1)
        InProcessAuthorizationServer { request, _ ->
            release.await()
            StubResponse(200, approvedBody(request))
        }.use { server ->
            val configuration = testConfiguration(
                mode = ExecutionMode.SINGLE,
                scenario = ScenarioName.CLEAR,
                baseUrl = server.baseUrl,
                requestTimeout = Duration.ofMillis(100),
            )
            try {
                val failed = assertIs<SubmissionResult.Failed>(submit(configuration, planned(configuration).single()))
                assertEquals("REQUEST_TIMEOUT", failed.failureCode)
                assertEquals(1, server.requests.size)
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `unavailable server is a sanitized failed submission`() {
        val port = ServerSocket(0).use { it.localPort }
        val configuration = testConfiguration(
            mode = ExecutionMode.SINGLE,
            scenario = ScenarioName.CLEAR,
            baseUrl = URI("http://127.0.0.1:$port"),
            connectTimeout = Duration.ofMillis(200),
        )

        val failed = assertIs<SubmissionResult.Failed>(submit(configuration, planned(configuration).single()))

        assertTrue(failed.failureCode in setOf("CONNECTION_UNAVAILABLE", "CONNECT_TIMEOUT"))
        assertNull(failed.httpStatus)
    }

    @Test
    fun `http technical failure is sent once without automatic retry`() {
        InProcessAuthorizationServer { _, _ ->
            StubResponse(500, "{\"code\":\"AUTHORIZATION_PROCESSING_ERROR\"}")
        }.use { server ->
            val configuration = testConfiguration(
                mode = ExecutionMode.SINGLE,
                scenario = ScenarioName.CLEAR,
                baseUrl = server.baseUrl,
            )

            assertIs<SubmissionResult.Failed>(submit(configuration, planned(configuration).single()))

            assertEquals(1, server.requests.size)
        }
    }

    private fun submit(
        configuration: SimulatorConfiguration,
        plan: PlannedSubmission,
    ): SubmissionResult = JdkAuthorizationGateway(
        configuration.baseUrl,
        configuration.connectTimeout,
        configuration.requestTimeout,
    ).use { gateway ->
        runBlocking { gateway.submit(plan) }
    }

    private fun errorCode(result: SubmissionResult): String {
        val completed = assertIs<SubmissionResult.Completed>(result)
        return assertIs<ParsedAuthorizationResponse.Error>(completed.response).code
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
