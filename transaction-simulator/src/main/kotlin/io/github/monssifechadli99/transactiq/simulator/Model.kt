package io.github.monssifechadli99.transactiq.simulator

import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class AuthorizationChannel {
    ECOMMERCE,
    POINT_OF_SALE,
}

data class SyntheticAuthorizationRequest(
    val requestId: UUID,
    val card: SyntheticCard,
    val merchantId: String,
    val merchantCategoryCode: String,
    val amount: String,
    val currency: String,
    val country: String,
    val channel: AuthorizationChannel,
    val transactionTime: Instant,
) {
    override fun toString(): String =
        "SyntheticAuthorizationRequest(requestId=$requestId, card=$card, merchantId=$merchantId, " +
            "merchantCategoryCode=$merchantCategoryCode, amount=$amount, currency=$currency, " +
            "country=$country, channel=$channel, transactionTime=$transactionTime)"
}

sealed interface ExpectedResponse {
    data object AnyAuthorizationDecision : ExpectedResponse

    data class Decision(
        val decision: String,
        val declineReason: String? = null,
    ) : ExpectedResponse

    data class Error(
        val status: Int,
        val code: String,
    ) : ExpectedResponse
}

data class PlannedSubmission(
    val sequence: Int,
    val scenario: ScenarioName?,
    val request: SyntheticAuthorizationRequest,
    val expectedResponse: ExpectedResponse?,
)

sealed interface ParsedAuthorizationResponse {
    data class Decision(
        val requestId: UUID,
        val decision: String,
        val declineReason: String?,
    ) : ParsedAuthorizationResponse

    data class Pending(
        val requestId: UUID,
    ) : ParsedAuthorizationResponse

    data class Error(
        val code: String,
    ) : ParsedAuthorizationResponse
}

sealed interface SubmissionResult {
    val plan: PlannedSubmission
    val latency: Duration
    val httpStatus: Int?

    data class Completed(
        override val plan: PlannedSubmission,
        override val latency: Duration,
        override val httpStatus: Int,
        val response: ParsedAuthorizationResponse,
    ) : SubmissionResult

    data class Failed(
        override val plan: PlannedSubmission,
        override val latency: Duration,
        override val httpStatus: Int?,
        val failureCode: String,
    ) : SubmissionResult
}

data class ScenarioStatus(
    val scenario: ScenarioName,
    val passed: Boolean,
)

data class RunResult(
    val configuration: SimulatorConfiguration,
    val submissions: List<SubmissionResult>,
    val scenarioStatuses: List<ScenarioStatus>,
) {
    val succeeded: Boolean = submissions.none { it is SubmissionResult.Failed } &&
        scenarioStatuses.all { it.passed }
}
