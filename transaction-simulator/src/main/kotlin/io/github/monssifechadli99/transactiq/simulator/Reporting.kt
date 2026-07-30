package io.github.monssifechadli99.transactiq.simulator

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import kotlin.math.ceil

class RunReporter {
    fun render(result: RunResult): String {
        val completed = result.submissions.filterIsInstance<SubmissionResult.Completed>()
        val failed = result.submissions.filterIsInstance<SubmissionResult.Failed>()
        val statuses = result.submissions.mapNotNull { it.httpStatus }
            .groupingBy { it }
            .eachCount()
            .toSortedMap()
        val decisions = completed.mapNotNull { it.response as? ParsedAuthorizationResponse.Decision }
        val declineReasons = decisions.mapNotNull { it.declineReason }
            .groupingBy { it }
            .eachCount()
            .toSortedMap()
        val latencies = result.submissions.map { it.latency }.sorted()

        return buildString {
            appendLine("runId=${result.configuration.runId}")
            appendLine("mode=${result.configuration.mode.cliName}")
            appendLine("totalRequests=${result.submissions.size}")
            appendLine("completedSubmissions=${completed.size}")
            appendLine("failedSubmissions=${failed.size}")
            appendLine("httpStatusCounts=${formatCounts(statuses)}")
            appendLine("approved=${decisions.count { it.decision == "APPROVED" }}")
            appendLine("declined=${decisions.count { it.decision == "DECLINED" }}")
            appendLine("declineReasonCounts=${formatCounts(declineReasons)}")
            appendLine("latencyMs=${formatLatency(latencies)}")
            result.scenarioStatuses.forEach {
                appendLine("scenario.${it.scenario.cliName}=${if (it.passed) "PASSED" else "FAILED"}")
            }
        }.trimEnd()
    }

    private fun formatCounts(counts: Map<*, Int>): String =
        if (counts.isEmpty()) "none" else counts.entries.joinToString(",") { "${it.key}:${it.value}" }

    private fun formatLatency(latencies: List<Duration>): String {
        if (latencies.isEmpty()) {
            return "min:n/a,median:n/a,p95:n/a,max:n/a"
        }
        val min = latencies.first()
        val median = percentile(latencies, 0.50)
        val p95 = percentile(latencies, 0.95)
        val max = latencies.last()
        return "min:${milliseconds(min)},median:${milliseconds(median)}," +
            "p95:${milliseconds(p95)},max:${milliseconds(max)}"
    }

    private fun percentile(values: List<Duration>, percentile: Double): Duration {
        val index = (ceil(percentile * values.size).toInt() - 1).coerceAtLeast(0)
        return values[index]
    }

    private fun milliseconds(duration: Duration): String = BigDecimal(duration.toNanos())
        .divide(BigDecimal("1000000"), 3, RoundingMode.HALF_UP)
        .toPlainString()
}
