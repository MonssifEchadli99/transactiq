package io.github.monssifechadli99.transactiq.simulator

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConfigurationTest {
    @Test
    fun `explicit cli values take precedence over environment defaults`() {
        val environment = mapOf(
            ConfigurationParser.ENV_MODE to "scenarios",
            ConfigurationParser.ENV_RUN_ID to "environment-run",
            ConfigurationParser.ENV_SEED to "1",
            ConfigurationParser.ENV_COUNT to "5",
            ConfigurationParser.ENV_CONCURRENCY to "2",
            ConfigurationParser.ENV_RATE to "3",
            ConfigurationParser.ENV_BASE_URL to "http://environment.example:8080",
            ConfigurationParser.ENV_CONNECT_TIMEOUT to "1s",
            ConfigurationParser.ENV_REQUEST_TIMEOUT to "2s",
        )

        val parsed = ConfigurationParser().parse(
            arrayOf(
                "--mode", "load",
                "--run-id=cli-run",
                "--seed", "7",
                "--count", "11",
                "--concurrency", "4",
                "--requests-per-second", "12.5",
                "--base-url", "http://cli.example:9090/root",
                "--connect-timeout", "1500ms",
                "--request-timeout", "PT4S",
            ),
            environment,
        ) as ConfigurationResult.Valid

        with(parsed.configuration) {
            assertEquals(ExecutionMode.LOAD, mode)
            assertEquals("cli-run", runId)
            assertEquals(7, seed)
            assertEquals(11, requestCount)
            assertEquals(4, concurrency)
            assertEquals(BigDecimal("12.5"), requestsPerSecond)
            assertEquals("http://cli.example:9090/root", baseUrl.toString())
            assertEquals(Duration.ofMillis(1500), connectTimeout)
            assertEquals(Duration.ofSeconds(4), requestTimeout)
        }
    }

    @Test
    fun `a default invocation receives a new run id`() {
        var sequence = 0
        val parser = ConfigurationParser { "generated-${++sequence}" }

        val first = (parser.parse(emptyArray(), emptyMap()) as ConfigurationResult.Valid).configuration
        val second = (parser.parse(emptyArray(), emptyMap()) as ConfigurationResult.Valid).configuration

        assertEquals("generated-1", first.runId)
        assertEquals("generated-2", second.runId)
        assertNotEquals(first.runId, second.runId)
    }

    @Test
    fun `non-positive numeric configuration is rejected before execution`() {
        val invalidArguments = listOf(
            arrayOf("--count", "0"),
            arrayOf("--concurrency", "-1"),
            arrayOf("--requests-per-second", "0"),
            arrayOf("--connect-timeout", "0ms"),
            arrayOf("--request-timeout", "-1s"),
        )

        invalidArguments.forEach { arguments ->
            assertFailsWith<ConfigurationException> {
                ConfigurationParser().parse(arguments, emptyMap())
            }
        }
    }

    @Test
    fun `malformed urls and invalid mode combinations are rejected`() {
        listOf(
            arrayOf("--base-url", "localhost:8080"),
            arrayOf("--base-url", "ftp://localhost"),
            arrayOf("--base-url", "http://user:password@localhost"),
            arrayOf("--base-url", "http://localhost?query=true"),
            arrayOf("--mode", "single"),
            arrayOf("--mode", "load", "--scenario", "clear"),
        ).forEach { arguments ->
            assertFailsWith<ConfigurationException> {
                ConfigurationParser().parse(arguments, emptyMap())
            }
        }
    }

    @Test
    fun `token values are redacted from configuration request and validation output`() {
        val rawToken = "tok_SyntheticSecret99"
        val configuration = testConfiguration(fundedToken = rawToken)
        val request = planned(configuration).first().request
        val outputBytes = ByteArrayOutputStream()

        val exit = SimulatorApplication.run(
            emptyArray(),
            mapOf(ConfigurationParser.ENV_FUNDED_CARD to "not-a-token-$rawToken"),
            PrintStream(outputBytes),
        )
        val rendered = configuration.cards.toString() + request.toString() + outputBytes.toString(Charsets.UTF_8)

        assertEquals(SimulatorApplication.EXIT_CONFIGURATION, exit)
        assertFalse(rendered.contains(rawToken))
        assertTrue(rendered.contains("funded/"))
    }

    @Test
    fun `token-shaped run identity is rejected without echoing it`() {
        val rawToken = "tok_RunIdentitySecret42"
        val outputBytes = ByteArrayOutputStream()

        val exit = SimulatorApplication.run(
            arrayOf("--run-id", "unsafe-$rawToken"),
            emptyMap(),
            PrintStream(outputBytes),
        )

        assertEquals(SimulatorApplication.EXIT_CONFIGURATION, exit)
        assertFalse(outputBytes.toString(Charsets.UTF_8).contains(rawToken))
    }

    @Test
    fun `help is successful runnable and contains no default raw card tokens`() {
        val outputBytes = ByteArrayOutputStream()

        val exit = SimulatorApplication.run(arrayOf("--help"), emptyMap(), PrintStream(outputBytes))
        val output = outputBytes.toString(Charsets.UTF_8)

        assertEquals(SimulatorApplication.EXIT_SUCCESS, exit)
        assertTrue(output.contains(".\\gradlew.bat :transaction-simulator:run"))
        assertFalse(output.contains("tok_A1B2C3D4"))
        assertFalse(output.contains("tok_insufficient01"))
    }
}
