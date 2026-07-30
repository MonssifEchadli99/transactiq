package io.github.monssifechadli99.transactiq.simulator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface NanoTimeSource {
    fun nanoTime(): Long
}

interface AuthorizationGateway : AutoCloseable {
    suspend fun submit(plan: PlannedSubmission): SubmissionResult

    override fun close() = Unit
}

class AuthorizationJson(
    private val mapper: JsonMapper = JsonMapper.builder().build(),
) {
    fun serialize(request: SyntheticAuthorizationRequest): String {
        val json = mapper.createObjectNode()
        json.put("requestId", request.requestId.toString())
        json.put("cardToken", request.card.rawToken)
        json.put("merchantId", request.merchantId)
        json.put("merchantCategoryCode", request.merchantCategoryCode)
        json.put("amount", BigDecimal(request.amount))
        json.put("currency", request.currency)
        json.put("country", request.country)
        json.put("channel", request.channel.name)
        json.put("transactionTime", request.transactionTime.toString())
        return mapper.writeValueAsString(json)
    }

    fun parse(status: Int, body: String, expectedRequestId: UUID): ParsedAuthorizationResponse {
        val root = try {
            mapper.readTree(body)
        } catch (_: RuntimeException) {
            throw MalformedResponseException()
        }
        if (!root.isObject) {
            throw MalformedResponseException()
        }
        return when (status) {
            200 -> parseDecision(root, expectedRequestId)
            202 -> parsePending(root, expectedRequestId)
            400 -> parseError(
                root,
                setOf(
                    "INVALID_AUTHORIZATION_REQUEST",
                    "MALFORMED_AUTHORIZATION_REQUEST",
                    "UNKNOWN_CARD_TOKEN",
                    "UNSUPPORTED_CURRENCY",
                ),
            )
            409 -> parseError(root, setOf("REQUEST_ID_CONFLICT"))
            500 -> parseError(root, setOf("AUTHORIZATION_PROCESSING_ERROR"))
            else -> throw UnexpectedHttpStatusException()
        }
    }

    private fun parseDecision(root: JsonNode, expectedRequestId: UUID): ParsedAuthorizationResponse.Decision {
        val requestId = parseAndVerifyRequestId(root, expectedRequestId)
        val decision = requiredText(root, "decision")
        val declineReason = root.get("declineReason")?.let {
            if (!it.isString || it.stringValue().isBlank()) {
                throw MalformedResponseException()
            }
            it.stringValue()
        }
        when (decision) {
            "APPROVED" -> if (declineReason != null) throw MalformedResponseException()
            "DECLINED" -> if (declineReason !in DECLINE_REASONS) throw MalformedResponseException()
            else -> throw MalformedResponseException()
        }
        return ParsedAuthorizationResponse.Decision(requestId, decision, declineReason)
    }

    private fun parsePending(root: JsonNode, expectedRequestId: UUID): ParsedAuthorizationResponse.Pending {
        val requestId = parseAndVerifyRequestId(root, expectedRequestId)
        if (requiredText(root, "status") != "PENDING") {
            throw MalformedResponseException()
        }
        return ParsedAuthorizationResponse.Pending(requestId)
    }

    private fun parseAndVerifyRequestId(root: JsonNode, expected: UUID): UUID {
        val requestId = try {
            UUID.fromString(requiredText(root, "requestId"))
        } catch (_: IllegalArgumentException) {
            throw MalformedResponseException()
        }
        if (requestId != expected) {
            throw MalformedResponseException()
        }
        return requestId
    }

    private fun requiredText(root: JsonNode, name: String): String {
        val node = root.get(name)
        if (node == null || !node.isString || node.stringValue().isBlank()) {
            throw MalformedResponseException()
        }
        return node.stringValue()
    }

    private fun parseError(root: JsonNode, allowedCodes: Set<String>): ParsedAuthorizationResponse.Error {
        val code = requiredText(root, "code")
        if (code !in allowedCodes) {
            throw MalformedResponseException()
        }
        return ParsedAuthorizationResponse.Error(code)
    }

    companion object {
        private val DECLINE_REASONS = setOf("INSUFFICIENT_FUNDS", "HIGH_FRAUD_RISK")
    }
}

class JdkAuthorizationGateway(
    baseUrl: URI,
    connectTimeout: Duration,
    private val requestTimeout: Duration,
    private val json: AuthorizationJson = AuthorizationJson(),
    private val nanoTimeSource: NanoTimeSource = NanoTimeSource(System::nanoTime),
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) : AuthorizationGateway {
    private val endpoint = URI(baseUrl.toString().trimEnd('/') + AUTHORIZATION_PATH)

    override suspend fun submit(plan: PlannedSubmission): SubmissionResult {
        val started = nanoTimeSource.nanoTime()
        var receivedStatus: Int? = null
        return try {
            val body = json.serialize(plan.request)
            val request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            receivedStatus = response.statusCode()
            val latency = elapsed(started)
            val parsed = json.parse(response.statusCode(), response.body(), plan.request.requestId)
            if (response.statusCode() == 500) {
                SubmissionResult.Failed(
                    plan,
                    latency,
                    response.statusCode(),
                    "HTTP_TECHNICAL_FAILURE",
                )
            } else {
                SubmissionResult.Completed(plan, latency, response.statusCode(), parsed)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: MalformedResponseException) {
            SubmissionResult.Failed(plan, elapsed(started), receivedStatus, "MALFORMED_RESPONSE")
        } catch (_: UnexpectedHttpStatusException) {
            SubmissionResult.Failed(plan, elapsed(started), receivedStatus, "UNEXPECTED_HTTP_STATUS")
        } catch (exception: Throwable) {
            val cause = exception.unwrapCompletionException()
            val code = when (cause) {
                is HttpConnectTimeoutException -> "CONNECT_TIMEOUT"
                is HttpTimeoutException -> "REQUEST_TIMEOUT"
                is ConnectException -> "CONNECTION_UNAVAILABLE"
                else -> "HTTP_CLIENT_FAILURE"
            }
            SubmissionResult.Failed(plan, elapsed(started), null, code)
        }
    }

    override fun close() {
        client.close()
    }

    private fun elapsed(started: Long): Duration =
        Duration.ofNanos((nanoTimeSource.nanoTime() - started).coerceAtLeast(0))

    companion object {
        const val AUTHORIZATION_PATH = "/api/v1/authorizations"
    }
}

private class MalformedResponseException : RuntimeException()

private class UnexpectedHttpStatusException : RuntimeException()

private tailrec fun Throwable.unwrapCompletionException(): Throwable = when (this) {
    is CompletionException,
    is ExecutionException,
    -> cause?.unwrapCompletionException() ?: this

    else -> this
}

private suspend fun <T> java.util.concurrent.CompletableFuture<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        whenComplete { value, failure ->
            if (failure == null) {
                continuation.resume(value)
            } else {
                continuation.resumeWithException(failure)
            }
        }
        continuation.invokeOnCancellation { cancel(true) }
    }
