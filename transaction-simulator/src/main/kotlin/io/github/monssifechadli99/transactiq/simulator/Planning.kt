package io.github.monssifechadli99.transactiq.simulator

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.SplittableRandom
import java.util.UUID

fun interface DeterministicRandomSource {
    fun nextInt(namespace: String, index: Int, bound: Int): Int
}

class SeededRandomSource(
    private val runId: String,
    private val seed: Long,
) : DeterministicRandomSource {
    override fun nextInt(namespace: String, index: Int, bound: Int): Int {
        require(bound > 0)
        val randomSeed = stableLong("$runId|$seed|$namespace|$index")
        return SplittableRandom(randomSeed).nextInt(bound)
    }
}

object DeterministicClock {
    private val epoch: Instant = Instant.parse("2025-01-01T00:00:00Z")
    private const val RANGE_SECONDS = 365L * 24 * 60 * 60

    fun forConfiguration(configuration: SimulatorConfiguration): Clock {
        val offset = Math.floorMod(
            stableLong("${configuration.runId}|${configuration.seed}|transaction-time"),
            RANGE_SECONDS,
        )
        return Clock.fixed(epoch.plusSeconds(offset), ZoneOffset.UTC)
    }
}

class RequestPlanner(
    private val configuration: SimulatorConfiguration,
    private val clock: Clock,
    private val random: DeterministicRandomSource,
) {
    fun plan(): List<PlannedSubmission> {
        val unsequenced = when (configuration.mode) {
            ExecutionMode.SCENARIOS -> ScenarioName.catalogOrder.flatMap(::scenario)
            ExecutionMode.SINGLE -> scenario(requireNotNull(configuration.scenario))
            ExecutionMode.LOAD -> load()
        }
        return unsequenced.mapIndexed { sequence, submission -> submission.copy(sequence = sequence) }
    }

    private fun scenario(name: ScenarioName): List<PlannedSubmission> = when (name) {
        ScenarioName.CLEAR -> listOf(
            submission(name, 0, request(name, 0), approved()),
        )

        ScenarioName.REVIEW_NON_DECLINING -> listOf(
            submission(
                name,
                0,
                request(name, 0, merchantId = "merchant-review"),
                approved(),
            ),
        )

        ScenarioName.HIGH_RISK -> listOf(
            submission(
                name,
                0,
                request(name, 0, merchantId = "merchant-high-risk"),
                declined("HIGH_FRAUD_RISK"),
            ),
        )

        ScenarioName.INSUFFICIENT_FUNDS -> listOf(
            submission(
                name,
                0,
                request(name, 0, card = configuration.cards.empty),
                declined("INSUFFICIENT_FUNDS"),
            ),
        )

        ScenarioName.HIGH_RISK_INSUFFICIENT_FUNDS -> listOf(
            submission(
                name,
                0,
                request(
                    name,
                    0,
                    card = configuration.cards.empty,
                    merchantId = "merchant-high-risk",
                ),
                declined("INSUFFICIENT_FUNDS"),
            ),
        )

        ScenarioName.COMPLETED_RETRY -> {
            val original = request(name, 0)
            listOf(
                submission(name, 0, original, approved()),
                submission(name, 0, original, approved()),
            )
        }

        ScenarioName.REQUEST_ID_CONFLICT -> {
            val original = request(name, 0)
            listOf(
                submission(name, 0, original, approved()),
                submission(
                    name,
                    0,
                    original.copy(amount = "2.00"),
                    ExpectedResponse.Error(409, "REQUEST_ID_CONFLICT"),
                ),
            )
        }

        ScenarioName.TRANSACTION_COUNT_VELOCITY -> (0 until 10).map { index ->
            submission(
                name,
                index,
                request(name, index, amount = "1.00"),
                if (index == 9) declined("HIGH_FRAUD_RISK") else ExpectedResponse.AnyAuthorizationDecision,
            )
        }

        ScenarioName.ROLLING_AMOUNT_VELOCITY -> (0 until 5).map { index ->
            submission(
                name,
                index,
                request(name, index, card = configuration.cards.empty, amount = "1000.00"),
                declined("INSUFFICIENT_FUNDS"),
            )
        }

        ScenarioName.COUNTRY_SWITCH_VELOCITY -> listOf(
            submission(
                name,
                0,
                request(name, 0, country = "DE"),
                ExpectedResponse.AnyAuthorizationDecision,
            ),
            submission(
                name,
                1,
                request(name, 1, country = "FR"),
                declined("HIGH_FRAUD_RISK"),
            ),
        )
    }

    private fun load(): List<PlannedSubmission> = (0 until configuration.requestCount).map { index ->
        val amountInCents = random.nextInt("load-amount", index, 500) + 1
        val channel = if (random.nextInt("load-channel", index, 2) == 0) {
            AuthorizationChannel.ECOMMERCE
        } else {
            AuthorizationChannel.POINT_OF_SALE
        }
        val request = request(
            scenario = null,
            index = index,
            merchantId = "load-${index.toString().padStart(6, '0')}",
            merchantCategoryCode = if (index % 2 == 0) "5732" else "5411",
            amount = BigDecimal.valueOf(amountInCents.toLong(), 2).toPlainString(),
            channel = channel,
        )
        PlannedSubmission(index, null, request, ExpectedResponse.AnyAuthorizationDecision)
    }

    private fun request(
        scenario: ScenarioName?,
        index: Int,
        card: SyntheticCard = configuration.cards.funded,
        merchantId: String = "merchant-clear",
        merchantCategoryCode: String = "5732",
        amount: String = "1.00",
        country: String = "DE",
        channel: AuthorizationChannel = AuthorizationChannel.ECOMMERCE,
    ): SyntheticAuthorizationRequest {
        val namespace = scenario?.cliName ?: "load"
        val timestampOffset = ((scenario?.ordinal ?: 100) * 1_000L) + index
        return SyntheticAuthorizationRequest(
            requestId = deterministicRequestId(configuration.runId, namespace, index),
            card = card,
            merchantId = merchantId,
            merchantCategoryCode = merchantCategoryCode,
            amount = amount,
            currency = "EUR",
            country = country,
            channel = channel,
            transactionTime = clock.instant().plusMillis(timestampOffset),
        )
    }

    private fun submission(
        scenario: ScenarioName,
        index: Int,
        request: SyntheticAuthorizationRequest,
        expectedResponse: ExpectedResponse,
    ): PlannedSubmission = PlannedSubmission(index, scenario, request, expectedResponse)

    private fun approved(): ExpectedResponse = ExpectedResponse.Decision("APPROVED")

    private fun declined(reason: String): ExpectedResponse = ExpectedResponse.Decision("DECLINED", reason)
}

fun deterministicRequestId(runId: String, scenario: String, index: Int): UUID =
    UUID.nameUUIDFromBytes("$runId|$scenario|$index".toByteArray(Charsets.UTF_8))

private fun stableLong(value: String): Long {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long
}
