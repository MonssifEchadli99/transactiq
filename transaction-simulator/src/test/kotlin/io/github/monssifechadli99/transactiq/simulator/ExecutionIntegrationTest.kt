package io.github.monssifechadli99.transactiq.simulator

import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExecutionIntegrationTest {
    @Test
    fun `completed retry sends two identical requests`() {
        InProcessAuthorizationServer { request, _ -> StubResponse(200, approvedBody(request)) }.use { server ->
            val result = executeSingle(server, ScenarioName.COMPLETED_RETRY, concurrency = 8)

            assertTrue(result.succeeded)
            assertEquals(2, server.requests.size)
            assertEquals(sha256(server.requests[0].body), sha256(server.requests[1].body))
        }
    }

    @Test
    fun `conflict sends the same request id with changed canonical content`() {
        InProcessAuthorizationServer { request, index ->
            if (index == 0) StubResponse(200, approvedBody(request))
            else StubResponse(409, "{\"code\":\"REQUEST_ID_CONFLICT\"}")
        }.use { server ->
            val result = executeSingle(server, ScenarioName.REQUEST_ID_CONFLICT, concurrency = 8)

            assertTrue(result.succeeded)
            assertEquals(2, server.requests.size)
            val first = TestJson.parse(server.requests[0].body)
            val second = TestJson.parse(server.requests[1].body)
            assertEquals(first.get("requestId").stringValue(), second.get("requestId").stringValue())
            assertEquals("1.00", TestJson.rawAmount(server.requests[0].body))
            assertEquals("2.00", TestJson.rawAmount(server.requests[1].body))
        }
    }

    @Test
    fun `bounded concurrency and exact load request count are enforced`() {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val initialWave = CountDownLatch(3)
        InProcessAuthorizationServer { request, _ ->
            val current = active.incrementAndGet()
            maximum.accumulateAndGet(current, ::maxOf)
            initialWave.countDown()
            check(initialWave.await(5, TimeUnit.SECONDS))
            try {
                StubResponse(200, approvedBody(request))
            } finally {
                active.decrementAndGet()
            }
        }.use { server ->
            val configuration = testConfiguration(
                mode = ExecutionMode.LOAD,
                requestCount = 9,
                concurrency = 3,
                baseUrl = server.baseUrl,
            )
            val plans = planned(configuration)
            val result = JdkAuthorizationGateway(
                server.baseUrl,
                configuration.connectTimeout,
                configuration.requestTimeout,
            ).use { gateway ->
                runBlocking { SimulatorEngine(gateway, NoRequestPacer).execute(configuration, plans) }
            }

            assertTrue(result.succeeded)
            assertEquals(9, result.submissions.size)
            assertEquals(9, server.requests.size)
            assertEquals(3, maximum.get())
        }
    }

    @Test
    fun `scheduled rate limiting uses injected time and suspension without sleeps`() = runBlocking {
        val clock = MutableNanoTimeSource()
        val delays = mutableListOf<Duration>()
        val pacer = ScheduledRequestPacer(
            BigDecimal("2"),
            clock,
            Suspension { duration ->
                delays += duration
                clock.advance(duration.toNanos())
            },
            maximumIndex = 2,
        )

        pacer.awaitTurn(0)
        pacer.awaitTurn(1)
        pacer.awaitTurn(2)

        assertEquals(listOf(Duration.ofMillis(500), Duration.ofMillis(500)), delays)
        assertEquals(Duration.ofSeconds(1).toNanos(), clock.nanoTime())
    }

    private fun executeSingle(
        server: InProcessAuthorizationServer,
        scenario: ScenarioName,
        concurrency: Int,
    ): RunResult {
        val configuration = testConfiguration(
            mode = ExecutionMode.SINGLE,
            scenario = scenario,
            concurrency = concurrency,
            baseUrl = server.baseUrl,
        )
        return JdkAuthorizationGateway(
            server.baseUrl,
            configuration.connectTimeout,
            configuration.requestTimeout,
        ).use { gateway ->
            runBlocking { SimulatorEngine(gateway, NoRequestPacer).execute(configuration, planned(configuration)) }
        }
    }

    private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private class MutableNanoTimeSource : NanoTimeSource {
        private var now: Long = 0

        override fun nanoTime(): Long = now

        fun advance(nanos: Long) {
            now += nanos
        }
    }
}
