# Transaction Simulator

## Purpose and boundary

`transaction-simulator` is a lightweight standalone Kotlin/JVM command-line application targeting
Java 21. It uses Kotlin coroutines and the JDK HTTP client to drive the existing authorization API:

```text
Simulator → authorization HTTP → fraud gRPC → PostgreSQL/outbox → Kafka
```

The simulator sends JSON only to `POST /api/v1/authorizations`. It does not call fraud-engine gRPC,
query PostgreSQL or Redis, publish to Kafka, consume Kafka, or depend on authorization-service
implementation classes. It adds no server and no second authorization ingress.

Kafka is intentionally not the request boundary. Authorization callers need the existing
synchronous HTTP validation, decision, error, and request-id idempotency semantics. Kafka carries
immutable completed-authorization facts asynchronously after the database/outbox transaction.
The simulator does not inspect those events; use the existing outbox and Kafka tooling separately.

## Modes

| Mode | Behavior |
| --- | --- |
| `scenarios` | Runs the complete deterministic catalog sequentially and reports each named expectation. |
| `single` | Runs one named scenario sequentially for local debugging; `--scenario` is required. |
| `load` | Precomputes exactly `--count` indexed requests, then submits them with bounded coroutine concurrency and optional request-start rate limiting. |

Scenario and single modes are sequential because retry, conflict, and velocity steps have causal
ordering. Load mode continues after individual business responses. Connection failures, timeouts,
malformed responses, unexpected statuses, and HTTP 500 technical responses are failed submissions.
There is no automatic HTTP retry in any mode.

## Deterministic scenario catalog

The catalog has this stable order:

| Scenario name | Synthetic sequence | Public expectation on a clean environment |
| --- | --- | --- |
| `clear` | Low-value funded-card purchase at an ordinary merchant. | `APPROVED`. |
| `review-non-declining` | Low-value purchase at documented `merchant-review`. | `APPROVED`; assessment details remain private. |
| `high-risk` | Low-value purchase at documented `merchant-high-risk`. | `DECLINED/HIGH_FRAUD_RISK`. |
| `insufficient-funds` | Low-value purchase with the empty-card alias. | `DECLINED/INSUFFICIENT_FUNDS`. |
| `high-risk-insufficient-funds` | Empty-card purchase at the high-risk merchant. | `DECLINED/INSUFFICIENT_FUNDS`, proving decline precedence. |
| `completed-retry` | The exact same request is sent twice after completion. | Both responses return the same approval. |
| `request-id-conflict` | The second request reuses the ID but changes the exact amount. | First approval, then HTTP 409 `REQUEST_ID_CONFLICT`. |
| `country-switch-velocity` | Same funded alias first in `DE`, then in `FR`. | The second response is `DECLINED/HIGH_FRAUD_RISK`. |
| `transaction-count-velocity` | Ten indexed EUR 1.00 attempts for the funded alias. | Every response is a decision and the final response is `DECLINED/HIGH_FRAUD_RISK`. |
| `rolling-amount-velocity` | Five indexed EUR 1,000.00 attempts for the empty alias. | Every public decline is `INSUFFICIENT_FUNDS`; the sequence crosses the documented rolling threshold, but insufficient funds has public decline precedence. |

The simulator uses documented merchant identifiers, MCCs, thresholds, and card aliases only to
construct these inputs. It does not reproduce fraud rules, calculate scores, or assert internal
matches. The public HTTP response deliberately does not expose fraud assessment evidence or score.

The default `funded` alias refers to the documented synthetic EUR account with EUR 1,000.00 posted
balance; `empty` refers to the documented zero-balance EUR account. Their raw tokens are never
printed by the simulator or included in commands. Optional token environment overrides must point
to explicitly seeded synthetic local accounts.

## Determinism, idempotency, and persistent state

Every planned request ID is deterministically derived from `runId`, scenario name, and request
index. The numeric seed drives indexed load choices. Transaction instants come from an injected
deterministic clock derived from the same run identity and seed, and exact decimal values are
serialized without floating-point conversion. Coroutine scheduling cannot change planned content.

Omitting `--run-id` creates and prints a new UUID. Reusing an explicit run ID with the same
configuration sends the same request IDs, decimal values, and exact ISO-8601 instants, intentionally
exercising authorization idempotency. The only duplicate send within a run is the explicit
`completed-retry` scenario; the HTTP client itself never retries.

PostgreSQL authorization state and Redis velocity state persist normally. The simulator has no
reset endpoint, cleanup call, direct datastore access, or Docker-volume operation. Stateful
scenario expectations therefore assume a clean, dedicated local environment. Reusing a card with
new run IDs can change later velocity results; that is correct production behavior, not simulator
nondeterminism.

## Configuration

Explicit CLI options override environment defaults.

| CLI option | Environment default | Default | Notes |
| --- | --- | --- | --- |
| `--mode` | `TRANSACTIQ_SIMULATOR_MODE` | `scenarios` | `scenarios`, `single`, or `load`. |
| `--scenario` | `TRANSACTIQ_SIMULATOR_SCENARIO` | none | Required only for `single`. |
| `--run-id` | `TRANSACTIQ_SIMULATOR_RUN_ID` | new UUID | Printed in every summary. |
| `--seed` | `TRANSACTIQ_SIMULATOR_SEED` | `0` | Signed whole number. |
| `--count` | `TRANSACTIQ_SIMULATOR_REQUEST_COUNT` | `100` | Positive load request count. |
| `--concurrency` | `TRANSACTIQ_SIMULATOR_CONCURRENCY` | `4` | Positive maximum in-flight load requests. |
| `--requests-per-second` | `TRANSACTIQ_SIMULATOR_REQUESTS_PER_SECOND` | unlimited | Optional positive decimal request-start rate. |
| `--base-url` | `TRANSACTIQ_AUTHORIZATION_BASE_URL` | `http://localhost:8080` | Absolute HTTP(S) URL without credentials, query, or fragment. |
| `--connect-timeout` | `TRANSACTIQ_SIMULATOR_CONNECT_TIMEOUT` | `2s` | Positive duration: `500ms`, `2s`, `1m`, or ISO format. |
| `--request-timeout` | `TRANSACTIQ_SIMULATOR_REQUEST_TIMEOUT` | `5s` | Positive duration in the same format. |

The optional fixture overrides are `TRANSACTIQ_SIMULATOR_FUNDED_CARD_TOKEN` and
`TRANSACTIQ_SIMULATOR_EMPTY_CARD_TOKEN`. They have no CLI equivalents so raw tokens do not enter
command history. Configuration errors name only the alias and never echo an override.

Examples:

```powershell
.\gradlew.bat :transaction-simulator:run --args="--mode scenarios --run-id demo-001 --seed 42"
.\gradlew.bat :transaction-simulator:run --args="--mode single --scenario country-switch-velocity --run-id country-debug-001"
.\gradlew.bat :transaction-simulator:run --args="--mode load --run-id load-001 --seed 42 --count 250 --concurrency 8 --requests-per-second 20"
.\gradlew.bat :transaction-simulator:run --args="--help"
```

## Concurrency, rate limiting, output, and security

Load requests are precomputed before coroutines start. A coroutine semaphore bounds in-flight HTTP
requests; the optional pacer schedules request starts without creating a thread per request.
Timing and suspension are injected so rate and latency behavior can be tested without sleeps.
Cancellation cancels the corresponding asynchronous JDK HTTP future, and the HTTP client is closed
after the run.

The deterministic-order summary contains the run ID and mode, total/completed/failed submissions,
sorted HTTP status counts, approved/declined counts, sorted exposed decline reasons, and latency
minimum/median/p95/maximum. Scenario modes add one `PASSED` or `FAILED` line per scenario. Exit code
`0` means there were no failed submissions and all applicable expectations passed; `2` is a
configuration failure and `3` is a connectivity, technical-response, or expectation failure.

Raw synthetic card tokens exist only in the required outbound authorization JSON body. Request JSON
and response bodies are never logged. Summaries, reports, errors, URLs, and examples use no raw
tokens; code-facing card representations use an alias and a lowercase SHA-256 fingerprint prefix.

## Local manual acceptance

Docker Desktop and Java 21 are required. Use a clean dedicated environment for the first scenario
catalog run.

1. Start infrastructure from the repository root:

   ```powershell
   docker compose up -d postgres redis kafka
   docker compose ps
   ```

2. Start fraud-engine in a second PowerShell window:

   ```powershell
   .\gradlew.bat :fraud-engine:bootRun
   ```

3. Start authorization-service in a third window:

   ```powershell
   .\gradlew.bat :authorization-service:bootRun
   ```

4. Run the catalog in a fourth window and confirm all ten scenario lines pass:

   ```powershell
   .\gradlew.bat :transaction-simulator:run --args="--mode scenarios --run-id acceptance-001 --seed 42"
   ```

5. Repeat that exact command to exercise stored idempotency, or use a new run ID to create new
   attempts and therefore new velocity observations.

6. Run controlled load if desired:

   ```powershell
   .\gradlew.bat :transaction-simulator:run --args="--mode load --run-id acceptance-load-001 --seed 42 --count 100 --concurrency 4 --requests-per-second 10"
   ```

7. Inspect PostgreSQL, outbox rows, and the Kafka topic with the commands in
   [Local Authorization and Event-Publication Acceptance](cycle-3-manual-acceptance.md). The
   simulator itself does not consume Kafka.

Stopping with `docker compose down` retains the PostgreSQL volume. The simulator never runs the
destructive volume-removal variant or clears Redis automatically.
