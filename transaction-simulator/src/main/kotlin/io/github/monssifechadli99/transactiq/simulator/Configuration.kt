package io.github.monssifechadli99.transactiq.simulator

import java.math.BigDecimal
import java.net.URI
import java.time.Duration
import java.util.UUID

enum class ExecutionMode(val cliName: String) {
    SCENARIOS("scenarios"),
    LOAD("load"),
    SINGLE("single");

    companion object {
        fun parse(value: String): ExecutionMode = entries.firstOrNull { it.cliName == value.lowercase() }
            ?: throw ConfigurationException("mode must be one of: scenarios, load, single")
    }
}

enum class ScenarioName(val cliName: String) {
    CLEAR("clear"),
    REVIEW_NON_DECLINING("review-non-declining"),
    HIGH_RISK("high-risk"),
    INSUFFICIENT_FUNDS("insufficient-funds"),
    HIGH_RISK_INSUFFICIENT_FUNDS("high-risk-insufficient-funds"),
    COMPLETED_RETRY("completed-retry"),
    REQUEST_ID_CONFLICT("request-id-conflict"),
    TRANSACTION_COUNT_VELOCITY("transaction-count-velocity"),
    ROLLING_AMOUNT_VELOCITY("rolling-amount-velocity"),
    COUNTRY_SWITCH_VELOCITY("country-switch-velocity");

    companion object {
        val catalogOrder: List<ScenarioName> = listOf(
            CLEAR,
            REVIEW_NON_DECLINING,
            HIGH_RISK,
            INSUFFICIENT_FUNDS,
            HIGH_RISK_INSUFFICIENT_FUNDS,
            COMPLETED_RETRY,
            REQUEST_ID_CONFLICT,
            COUNTRY_SWITCH_VELOCITY,
            TRANSACTION_COUNT_VELOCITY,
            ROLLING_AMOUNT_VELOCITY,
        )

        fun parse(value: String): ScenarioName = entries.firstOrNull { it.cliName == value.lowercase() }
            ?: throw ConfigurationException(
                "scenario must be one of: ${entries.joinToString(", ") { it.cliName }}",
            )
    }
}

class SyntheticCard private constructor(
    val alias: String,
    internal val rawToken: String,
) {
    val fingerprint: String = sha256Hex(rawToken)

    override fun equals(other: Any?): Boolean =
        other is SyntheticCard && alias == other.alias && rawToken == other.rawToken

    override fun hashCode(): Int = 31 * alias.hashCode() + rawToken.hashCode()

    override fun toString(): String = "$alias/${fingerprint.take(12)}"

    companion object {
        fun validated(alias: String, token: String): SyntheticCard {
            if (!TOKEN_PATTERN.matches(token)) {
                throw ConfigurationException("$alias card token is not a valid synthetic token")
            }
            return SyntheticCard(alias, token)
        }

        private val TOKEN_PATTERN = Regex("tok_[A-Za-z0-9]{8,60}")
    }
}

class SyntheticCards(
    val funded: SyntheticCard,
    val empty: SyntheticCard,
) {
    override fun toString(): String = "SyntheticCards(funded=$funded, empty=$empty)"
}

data class SimulatorConfiguration(
    val mode: ExecutionMode,
    val scenario: ScenarioName?,
    val runId: String,
    val seed: Long,
    val requestCount: Int,
    val concurrency: Int,
    val requestsPerSecond: BigDecimal?,
    val baseUrl: URI,
    val connectTimeout: Duration,
    val requestTimeout: Duration,
    val cards: SyntheticCards,
)

sealed interface ConfigurationResult {
    data object Help : ConfigurationResult

    data class Valid(val configuration: SimulatorConfiguration) : ConfigurationResult
}

class ConfigurationException(message: String) : IllegalArgumentException(message)

class ConfigurationParser(
    private val newRunId: () -> String = { UUID.randomUUID().toString() },
) {
    fun parse(args: Array<String>, environment: Map<String, String>): ConfigurationResult {
        val options = parseOptions(args)
        if (options.containsKey("help")) {
            return ConfigurationResult.Help
        }

        val mode = ExecutionMode.parse(value(options, "mode", environment, ENV_MODE) ?: "scenarios")
        val scenario = value(options, "scenario", environment, ENV_SCENARIO)?.let(ScenarioName::parse)
        val runId = value(options, "run-id", environment, ENV_RUN_ID) ?: newRunId()
        validateRunId(runId)
        val seed = parseLong(value(options, "seed", environment, ENV_SEED) ?: "0", "seed")
        val count = parsePositiveInt(
            value(options, "count", environment, ENV_COUNT) ?: "100",
            "count",
        )
        val concurrency = parsePositiveInt(
            value(options, "concurrency", environment, ENV_CONCURRENCY) ?: "4",
            "concurrency",
        )
        val requestsPerSecond = value(options, "requests-per-second", environment, ENV_RATE)
            ?.let { parsePositiveDecimal(it, "requests-per-second") }
        val baseUrl = parseBaseUrl(
            value(options, "base-url", environment, ENV_BASE_URL) ?: "http://localhost:8080",
        )
        val connectTimeout = parsePositiveDuration(
            value(options, "connect-timeout", environment, ENV_CONNECT_TIMEOUT) ?: "2s",
            "connect-timeout",
        )
        val requestTimeout = parsePositiveDuration(
            value(options, "request-timeout", environment, ENV_REQUEST_TIMEOUT) ?: "5s",
            "request-timeout",
        )
        val cards = SyntheticCards(
            funded = SyntheticCard.validated(
                "funded",
                environment[ENV_FUNDED_CARD] ?: "tok_A1B2C3D4",
            ),
            empty = SyntheticCard.validated(
                "empty",
                environment[ENV_EMPTY_CARD] ?: "tok_insufficient01",
            ),
        )

        when (mode) {
            ExecutionMode.SINGLE -> if (scenario == null) {
                throw ConfigurationException("single mode requires --scenario")
            }

            ExecutionMode.SCENARIOS,
            ExecutionMode.LOAD,
            -> if (scenario != null) {
                throw ConfigurationException("--scenario is supported only in single mode")
            }
        }

        return ConfigurationResult.Valid(
            SimulatorConfiguration(
                mode = mode,
                scenario = scenario,
                runId = runId,
                seed = seed,
                requestCount = count,
                concurrency = concurrency,
                requestsPerSecond = requestsPerSecond,
                baseUrl = baseUrl,
                connectTimeout = connectTimeout,
                requestTimeout = requestTimeout,
                cards = cards,
            ),
        )
    }

    private fun parseOptions(args: Array<String>): Map<String, String> {
        val values = linkedMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            val argument = args[index]
            if (argument == "--help" || argument == "-h") {
                values["help"] = "true"
                index++
                continue
            }
            if (!argument.startsWith("--")) {
                throw ConfigurationException("unexpected positional argument")
            }
            val option = argument.substring(2)
            val separator = option.indexOf('=')
            val name: String
            val optionValue: String
            if (separator >= 0) {
                name = option.substring(0, separator)
                optionValue = option.substring(separator + 1)
            } else {
                name = option
                if (index + 1 >= args.size || args[index + 1].startsWith("--")) {
                    throw ConfigurationException("--$name requires a value")
                }
                optionValue = args[++index]
            }
            if (name !in SUPPORTED_OPTIONS) {
                throw ConfigurationException("unknown option --$name")
            }
            if (optionValue.isBlank()) {
                throw ConfigurationException("--$name requires a non-blank value")
            }
            if (values.put(name, optionValue) != null) {
                throw ConfigurationException("--$name may be specified only once")
            }
            index++
        }
        return values
    }

    private fun value(
        options: Map<String, String>,
        option: String,
        environment: Map<String, String>,
        environmentName: String,
    ): String? = options[option] ?: environment[environmentName]?.takeIf { it.isNotBlank() }

    private fun validateRunId(value: String) {
        if (value.isBlank() || value.length > 128 || value.any { it.isISOControl() }) {
            throw ConfigurationException("run-id must contain 1 to 128 printable characters")
        }
        if (TOKEN_SHAPED_VALUE.containsMatchIn(value)) {
            throw ConfigurationException("run-id must not contain a card-token-shaped value")
        }
    }

    private fun parseLong(value: String, name: String): Long = value.toLongOrNull()
        ?: throw ConfigurationException("$name must be a whole number")

    private fun parsePositiveInt(value: String, name: String): Int {
        val parsed = value.toIntOrNull() ?: throw ConfigurationException("$name must be a whole number")
        if (parsed <= 0) {
            throw ConfigurationException("$name must be positive")
        }
        return parsed
    }

    private fun parsePositiveDecimal(value: String, name: String): BigDecimal {
        val parsed = try {
            BigDecimal(value)
        } catch (_: NumberFormatException) {
            throw ConfigurationException("$name must be a decimal number")
        }
        if (parsed.signum() <= 0) {
            throw ConfigurationException("$name must be positive")
        }
        return parsed
    }

    private fun parsePositiveDuration(value: String, name: String): Duration {
        val duration = try {
            when {
                value.startsWith("P", ignoreCase = true) -> Duration.parse(value.uppercase())
                value.endsWith("ms", ignoreCase = true) -> Duration.ofMillis(value.dropLast(2).toLong())
                value.endsWith("s", ignoreCase = true) -> Duration.ofSeconds(value.dropLast(1).toLong())
                value.endsWith("m", ignoreCase = true) -> Duration.ofMinutes(value.dropLast(1).toLong())
                else -> throw IllegalArgumentException()
            }
        } catch (_: RuntimeException) {
            throw ConfigurationException("$name must be a duration such as 500ms, 2s, 1m, or PT2S")
        }
        if (duration.isZero || duration.isNegative) {
            throw ConfigurationException("$name must be positive")
        }
        return duration
    }

    private fun parseBaseUrl(value: String): URI {
        if (TOKEN_SHAPED_VALUE.containsMatchIn(value)) {
            throw ConfigurationException("base-url must not contain a card-token-shaped value")
        }
        val uri = try {
            URI(value)
        } catch (_: RuntimeException) {
            throw ConfigurationException("base-url must be a valid absolute HTTP URL")
        }
        if (!uri.isAbsolute || uri.scheme.lowercase() !in setOf("http", "https") || uri.host == null ||
            uri.rawQuery != null || uri.rawFragment != null || uri.userInfo != null
        ) {
            throw ConfigurationException("base-url must be an absolute HTTP URL without credentials, query, or fragment")
        }
        return URI(uri.toString().trimEnd('/'))
    }

    companion object {
        const val ENV_MODE = "TRANSACTIQ_SIMULATOR_MODE"
        const val ENV_SCENARIO = "TRANSACTIQ_SIMULATOR_SCENARIO"
        const val ENV_RUN_ID = "TRANSACTIQ_SIMULATOR_RUN_ID"
        const val ENV_SEED = "TRANSACTIQ_SIMULATOR_SEED"
        const val ENV_COUNT = "TRANSACTIQ_SIMULATOR_REQUEST_COUNT"
        const val ENV_CONCURRENCY = "TRANSACTIQ_SIMULATOR_CONCURRENCY"
        const val ENV_RATE = "TRANSACTIQ_SIMULATOR_REQUESTS_PER_SECOND"
        const val ENV_BASE_URL = "TRANSACTIQ_AUTHORIZATION_BASE_URL"
        const val ENV_CONNECT_TIMEOUT = "TRANSACTIQ_SIMULATOR_CONNECT_TIMEOUT"
        const val ENV_REQUEST_TIMEOUT = "TRANSACTIQ_SIMULATOR_REQUEST_TIMEOUT"
        const val ENV_FUNDED_CARD = "TRANSACTIQ_SIMULATOR_FUNDED_CARD_TOKEN"
        const val ENV_EMPTY_CARD = "TRANSACTIQ_SIMULATOR_EMPTY_CARD_TOKEN"

        private val SUPPORTED_OPTIONS = setOf(
            "mode",
            "scenario",
            "run-id",
            "seed",
            "count",
            "concurrency",
            "requests-per-second",
            "base-url",
            "connect-timeout",
            "request-timeout",
        )
        private val TOKEN_SHAPED_VALUE = Regex("tok_[A-Za-z0-9]{8,60}")
    }
}

private fun sha256Hex(value: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
