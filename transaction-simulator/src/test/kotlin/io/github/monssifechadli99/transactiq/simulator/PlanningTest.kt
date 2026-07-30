package io.github.monssifechadli99.transactiq.simulator

import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlanningTest {
    @Test
    fun `same run identity and seed produce identical indexed requests`() {
        val configuration = testConfiguration(mode = ExecutionMode.LOAD, requestCount = 25)

        val first = planned(configuration).map { it.request }
        val second = planned(configuration).map { it.request }

        assertEquals(first, second)
    }

    @Test
    fun `different indexes have unique deterministic request identifiers`() {
        val requests = planned(testConfiguration(mode = ExecutionMode.LOAD, requestCount = 100))

        assertEquals(100, requests.map { it.request.requestId }.toSet().size)
        assertNotEquals(requests[0].request.requestId, requests[1].request.requestId)
    }

    @Test
    fun `request serialization preserves exact decimal and instant fields`() {
        val configuration = testConfiguration(
            mode = ExecutionMode.SINGLE,
            scenario = ScenarioName.ROLLING_AMOUNT_VELOCITY,
        )
        val fixedClock = Clock.fixed(Instant.parse("2026-02-03T04:05:06.123456789Z"), ZoneOffset.UTC)
        val request = RequestPlanner(
            configuration,
            fixedClock,
            SeededRandomSource(configuration.runId, configuration.seed),
        ).plan().first().request

        val serialized = AuthorizationJson().serialize(request)
        val tree = JsonMapper.builder().build().readTree(serialized)

        assertEquals("1000.00", TestJson.rawAmount(serialized))
        assertEquals(request.transactionTime.toString(), tree.get("transactionTime").stringValue())
        assertEquals("2026-02-03T04:05:14.123456789Z", request.transactionTime.toString())
    }

    @Test
    fun `scenario catalog has stable documented order`() {
        val scenarioOrder = planned(testConfiguration())
            .mapNotNull { it.scenario }
            .distinct()

        assertEquals(ScenarioName.catalogOrder, scenarioOrder)
        assertEquals(
            listOf(
                "clear",
                "review-non-declining",
                "high-risk",
                "insufficient-funds",
                "high-risk-insufficient-funds",
                "completed-retry",
                "request-id-conflict",
                "country-switch-velocity",
                "transaction-count-velocity",
                "rolling-amount-velocity",
            ),
            scenarioOrder.map { it.cliName },
        )
    }

    @Test
    fun `completed retry is identical and conflict changes content under the same request id`() {
        val retry = planned(
            testConfiguration(mode = ExecutionMode.SINGLE, scenario = ScenarioName.COMPLETED_RETRY),
        )
        val conflict = planned(
            testConfiguration(mode = ExecutionMode.SINGLE, scenario = ScenarioName.REQUEST_ID_CONFLICT),
        )

        assertEquals(retry[0].request, retry[1].request)
        assertEquals(conflict[0].request.requestId, conflict[1].request.requestId)
        assertNotEquals(conflict[0].request.amount, conflict[1].request.amount)
        assertTrue(conflict[0].request.copy(amount = conflict[1].request.amount) == conflict[1].request)
    }
}
