package io.github.monssifechadli99.transactiq.simulator

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.PrintStream
import kotlin.system.exitProcess
import kotlin.time.toKotlinDuration

object SimulatorApplication {
    const val EXIT_SUCCESS = 0
    const val EXIT_CONFIGURATION = 2
    const val EXIT_EXECUTION = 3

    fun run(
        args: Array<String>,
        environment: Map<String, String> = System.getenv(),
        output: PrintStream = System.out,
        parser: ConfigurationParser = ConfigurationParser(),
        gatewayFactory: (SimulatorConfiguration) -> AuthorizationGateway = { configuration ->
            JdkAuthorizationGateway(
                configuration.baseUrl,
                configuration.connectTimeout,
                configuration.requestTimeout,
            )
        },
        nanoTimeSource: NanoTimeSource = NanoTimeSource(System::nanoTime),
        suspension: Suspension = Suspension { duration -> delay(duration.toKotlinDuration()) },
    ): Int {
        val configuration = try {
            when (val parsed = parser.parse(args, environment)) {
                ConfigurationResult.Help -> {
                    output.println(HELP)
                    return EXIT_SUCCESS
                }

                is ConfigurationResult.Valid -> parsed.configuration
            }
        } catch (exception: ConfigurationException) {
            output.println("configurationError=${exception.message}")
            return EXIT_CONFIGURATION
        }

        return try {
            val planner = RequestPlanner(
                configuration,
                DeterministicClock.forConfiguration(configuration),
                SeededRandomSource(configuration.runId, configuration.seed),
            )
            val plans = planner.plan()
            val pacer = configuration.requestsPerSecond?.let {
                ScheduledRequestPacer(it, nanoTimeSource, suspension, plans.lastIndex)
            } ?: NoRequestPacer
            val result = gatewayFactory(configuration).use { gateway ->
                runBlocking { SimulatorEngine(gateway, pacer).execute(configuration, plans) }
            }
            output.println(RunReporter().render(result))
            if (result.succeeded) EXIT_SUCCESS else EXIT_EXECUTION
        } catch (_: ConfigurationException) {
            output.println("configurationError=requests-per-second produces an unsupported schedule duration")
            EXIT_CONFIGURATION
        } catch (_: Throwable) {
            output.println("executionError=INTERNAL_SIMULATOR_FAILURE")
            EXIT_EXECUTION
        }
    }

    val HELP: String = """
        TransactIQ synthetic transaction simulator

        Usage:
          transaction-simulator [options]

        Options:
          --mode <scenarios|load|single>       Execution mode (default: scenarios)
          --scenario <name>                   Named scenario; required for single mode
          --run-id <value>                    Deterministic run identity (default: new UUID)
          --seed <whole-number>                Deterministic numeric seed (default: 0)
          --count <positive-number>            Load request count (default: 100)
          --concurrency <positive-number>      Maximum in-flight requests (default: 4)
          --requests-per-second <positive>     Optional request-start rate limit
          --base-url <http(s)-url>             Authorization base URL
          --connect-timeout <duration>         For example 500ms, 2s, 1m, or PT2S
          --request-timeout <duration>         For example 500ms, 5s, 1m, or PT5S
          --help                               Show this help

        Scenario names:
          ${ScenarioName.catalogOrder.joinToString(", ") { it.cliName }}

        Environment defaults:
          TRANSACTIQ_SIMULATOR_MODE, TRANSACTIQ_SIMULATOR_SCENARIO,
          TRANSACTIQ_SIMULATOR_RUN_ID, TRANSACTIQ_SIMULATOR_SEED,
          TRANSACTIQ_SIMULATOR_REQUEST_COUNT, TRANSACTIQ_SIMULATOR_CONCURRENCY,
          TRANSACTIQ_SIMULATOR_REQUESTS_PER_SECOND, TRANSACTIQ_AUTHORIZATION_BASE_URL,
          TRANSACTIQ_SIMULATOR_CONNECT_TIMEOUT, TRANSACTIQ_SIMULATOR_REQUEST_TIMEOUT.
          Synthetic card fixtures can be overridden through the documented token environment variables.

        Windows examples:
          .\gradlew.bat :transaction-simulator:run --args="--mode scenarios --run-id demo-001 --seed 42"
          .\gradlew.bat :transaction-simulator:run --args="--mode single --scenario high-risk --run-id debug-001"
          .\gradlew.bat :transaction-simulator:run --args="--mode load --count 100 --concurrency 8 --requests-per-second 20"
    """.trimIndent()
}

fun main(args: Array<String>) {
    exitProcess(SimulatorApplication.run(args))
}
