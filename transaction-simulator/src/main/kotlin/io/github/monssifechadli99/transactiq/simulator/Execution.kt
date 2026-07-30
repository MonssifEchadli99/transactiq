package io.github.monssifechadli99.transactiq.simulator

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration

fun interface Suspension {
    suspend fun delay(duration: Duration)
}

fun interface RequestPacer {
    suspend fun awaitTurn(index: Int)
}

object NoRequestPacer : RequestPacer {
    override suspend fun awaitTurn(index: Int) = Unit
}

class ScheduledRequestPacer(
    private val requestsPerSecond: BigDecimal,
    private val nanoTimeSource: NanoTimeSource,
    private val suspension: Suspension,
    maximumIndex: Int,
) : RequestPacer {
    private val startedAt = nanoTimeSource.nanoTime()

    init {
        scheduledOffset(maximumIndex.coerceAtLeast(0))
    }

    override suspend fun awaitTurn(index: Int) {
        val offsetNanos = scheduledOffset(index)
        val elapsed = nanoTimeSource.nanoTime() - startedAt
        val remaining = offsetNanos - elapsed
        if (remaining > 0) {
            suspension.delay(Duration.ofNanos(remaining))
        }
    }

    private fun scheduledOffset(index: Int): Long {
        val offset = BigDecimal(index)
            .multiply(NANOS_PER_SECOND)
            .divide(requestsPerSecond, 0, RoundingMode.FLOOR)
        return try {
            offset.longValueExact()
        } catch (_: ArithmeticException) {
            throw ConfigurationException("requests-per-second produces an unsupported schedule duration")
        }
    }

    companion object {
        private val NANOS_PER_SECOND = BigDecimal("1000000000")
    }
}

class SimulatorEngine(
    private val gateway: AuthorizationGateway,
    private val pacer: RequestPacer,
) {
    suspend fun execute(
        configuration: SimulatorConfiguration,
        plans: List<PlannedSubmission>,
    ): RunResult {
        val results = if (configuration.mode == ExecutionMode.LOAD) {
            executeConcurrent(configuration, plans)
        } else {
            plans.map { plan ->
                pacer.awaitTurn(plan.sequence)
                gateway.submit(plan)
            }
        }

        return RunResult(
            configuration = configuration,
            submissions = results,
            scenarioStatuses = evaluateScenarios(configuration, results),
        )
    }

    private suspend fun executeConcurrent(
        configuration: SimulatorConfiguration,
        plans: List<PlannedSubmission>,
    ): List<SubmissionResult> = coroutineScope {
        val semaphore = Semaphore(configuration.concurrency)
        plans.map { plan ->
            async {
                pacer.awaitTurn(plan.sequence)
                semaphore.withPermit { gateway.submit(plan) }
            }
        }.awaitAll().sortedBy { it.plan.sequence }
    }

    private fun evaluateScenarios(
        configuration: SimulatorConfiguration,
        results: List<SubmissionResult>,
    ): List<ScenarioStatus> {
        val order = when (configuration.mode) {
            ExecutionMode.SCENARIOS -> ScenarioName.catalogOrder
            ExecutionMode.SINGLE -> listOf(requireNotNull(configuration.scenario))
            ExecutionMode.LOAD -> emptyList()
        }
        return order.map { scenario ->
            val scenarioResults = results.filter { it.plan.scenario == scenario }
            ScenarioStatus(
                scenario,
                scenarioResults.isNotEmpty() && scenarioResults.all(::matchesExpectation),
            )
        }
    }

    private fun matchesExpectation(result: SubmissionResult): Boolean {
        if (result !is SubmissionResult.Completed) {
            return false
        }
        return when (val expected = result.plan.expectedResponse) {
            null -> true
            ExpectedResponse.AnyAuthorizationDecision -> result.httpStatus == 200 &&
                result.response is ParsedAuthorizationResponse.Decision

            is ExpectedResponse.Decision -> {
                val response = result.response as? ParsedAuthorizationResponse.Decision
                result.httpStatus == 200 && response?.decision == expected.decision &&
                    response.declineReason == expected.declineReason
            }

            is ExpectedResponse.Error -> {
                val response = result.response as? ParsedAuthorizationResponse.Error
                result.httpStatus == expected.status && response?.code == expected.code
            }
        }
    }
}
