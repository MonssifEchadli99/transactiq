package io.github.monssifechadli99.transactiq.simulator

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportingTest {
    @Test
    fun `summary ordering counts percentiles and scenario state are stable`() {
        val configuration = testConfiguration(
            mode = ExecutionMode.SINGLE,
            scenario = ScenarioName.CLEAR,
            runId = "report-run",
        )
        val plans = planned(configuration)
        val approved = SubmissionResult.Completed(
            plans[0],
            Duration.ofMillis(10),
            200,
            ParsedAuthorizationResponse.Decision(plans[0].request.requestId, "APPROVED", null),
        )
        val declinedPlan = plans[0].copy(sequence = 1)
        val declined = SubmissionResult.Completed(
            declinedPlan,
            Duration.ofMillis(20),
            200,
            ParsedAuthorizationResponse.Decision(
                declinedPlan.request.requestId,
                "DECLINED",
                "HIGH_FRAUD_RISK",
            ),
        )
        val failed = SubmissionResult.Failed(
            plans[0].copy(sequence = 2),
            Duration.ofMillis(30),
            500,
            "HTTP_TECHNICAL_FAILURE",
        )

        val summary = RunReporter().render(
            RunResult(configuration, listOf(approved, declined, failed), listOf(ScenarioStatus(ScenarioName.CLEAR, false))),
        )

        assertEquals(
            """
                runId=report-run
                mode=single
                totalRequests=3
                completedSubmissions=2
                failedSubmissions=1
                httpStatusCounts=200:2,500:1
                approved=1
                declined=1
                declineReasonCounts=HIGH_FRAUD_RISK:1
                latencyMs=min:10.000,median:20.000,p95:30.000,max:30.000
                scenario.clear=FAILED
            """.trimIndent(),
            summary,
        )
    }

    @Test
    fun `application returns stable success and expectation failure exit codes`() {
        InProcessAuthorizationServer { request, _ -> StubResponse(200, approvedBody(request)) }.use { server ->
            val output = ByteArrayOutputStream()
            val exit = SimulatorApplication.run(
                arrayOf(
                    "--mode", "single",
                    "--scenario", "clear",
                    "--run-id", "exit-success",
                    "--base-url", server.baseUrl.toString(),
                ),
                emptyMap(),
                PrintStream(output),
            )

            assertEquals(SimulatorApplication.EXIT_SUCCESS, exit)
            assertTrue(output.toString(Charsets.UTF_8).contains("scenario.clear=PASSED"))
        }

        InProcessAuthorizationServer { request, _ ->
            StubResponse(200, declinedBody(request, "HIGH_FRAUD_RISK"))
        }.use { server ->
            val output = ByteArrayOutputStream()
            val exit = SimulatorApplication.run(
                arrayOf(
                    "--mode", "single",
                    "--scenario", "clear",
                    "--run-id", "exit-failure",
                    "--base-url", server.baseUrl.toString(),
                ),
                emptyMap(),
                PrintStream(output),
            )

            assertEquals(SimulatorApplication.EXIT_EXECUTION, exit)
            assertTrue(output.toString(Charsets.UTF_8).contains("scenario.clear=FAILED"))
        }
    }

    @Test
    fun `summary and transport errors never expose raw tokens`() {
        val rawToken = "tok_SummarySecret42"
        val configuration = testConfiguration(
            mode = ExecutionMode.SINGLE,
            scenario = ScenarioName.CLEAR,
            fundedToken = rawToken,
        )
        val plan = planned(configuration).single()
        val result = RunResult(
            configuration,
            listOf(SubmissionResult.Failed(plan, Duration.ZERO, null, "CONNECTION_UNAVAILABLE")),
            listOf(ScenarioStatus(ScenarioName.CLEAR, false)),
        )

        val rendered = RunReporter().render(result)

        assertFalse(rendered.contains(rawToken))
        assertFalse(plan.toString().contains(rawToken))
    }
}
