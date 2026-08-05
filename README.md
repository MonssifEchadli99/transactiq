# TransactIQ

TransactIQ is a public portfolio project for a synthetic real-time transaction-intelligence
platform. All cards, merchants, customers, transactions, balances, and fraud signals are
demonstration data.

## Current architecture

```text
Simulator → authorization HTTP → fraud gRPC → PostgreSQL/outbox → Kafka
                                                                    ↓
                                                   case management → PostgreSQL
```

The Kotlin transaction simulator is the merchant/acquirer-side driver. It calls only
`POST /api/v1/authorizations`; it has no dependency on service implementation classes and no
direct access to gRPC, PostgreSQL, Redis, or Kafka. Kafka is a downstream, asynchronous channel for
facts about completed authorizations, not an authorization ingress. The HTTP boundary owns the
synchronous response, validation, and idempotency contract.

Current modules:

- `authorization-service`: HTTP authorization workflow, PostgreSQL ledger/reservations, outbox,
  fraud gRPC client, and Kafka outbox relay.
- `case-management-service`: idempotent Kafka consumer, immutable PostgreSQL fraud-case snapshots,
  bounded failure recovery, analyst APIs, and a transactional Fraud Case projection outbox.
- `case-projection-contract`: versioned full-snapshot Fraud Case projection Protobuf contract.
- `case-search-service`: Kotlin Kafka-to-OpenSearch indexer and eventually consistent Fraud Case
  search API.
- `investigation-assistant-service`: Kafka-to-OpenSearch safe evidence indexer, OpenAI embeddings
  through Spring AI, and a read-only hybrid (BM25 + k-NN + RRF) retrieval API for a future,
  advisory-only AI fraud-investigation assistant. Generates no AI answers yet.
- `fraud-contract`: versioned fraud-assessment gRPC contract.
- `fraud-engine`: deterministic synthetic stateless and velocity fraud rules backed by Redis.
- `event-contract`: versioned authorization-completed Protobuf contract.
- `transaction-simulator`: standalone Kotlin/JVM CLI for scenarios and controlled load.

## Local run

Java 21 and Docker Desktop are required. From PowerShell at the repository root:

```powershell
docker compose up -d postgres case-postgres redis kafka opensearch
```

Start the two services in separate PowerShell windows:

```powershell
.\gradlew.bat :fraud-engine:bootRun
```

```powershell
.\gradlew.bat :authorization-service:bootRun
```

Start case management in another window:

```powershell
.\gradlew.bat :case-management-service:bootRun
```

Its development-only analyst API is under `/api/v1/fraud-cases`. Claim, resolution, and
`assignment=MINE` use caller-supplied `X-Analyst-Id`; this is deliberately not authentication or
authorization. Resolution requires `CONFIRMED_FRAUD` or `FALSE_POSITIVE` plus a normalized,
synthetic rationale. Lifecycle-history reads do not require a fake identity header.

Start `case-search-service` with Kafka and OpenSearch available to use
`GET /api/v1/fraud-cases/search`. It queries only the OpenSearch read alias and may lag PostgreSQL.
Cycle 5 is complete with no authentication, UI, or AI behavior.

Start `investigation-assistant-service` with Kafka, OpenSearch, and a nonblank effective OpenAI
embedding key to use `POST /api/v1/fraud-cases/{caseId}/investigation/retrieval`. The default path
uses `OPENAI_API_KEY`; an explicitly configured `spring.ai.openai.embedding.api-key` takes
precedence and must itself be nonblank. `OPENAI_LOG` must be absent or set to `off` so SDK
diagnostics cannot bypass the service's logging controls:

```powershell
$env:OPENAI_API_KEY = "<your-key>"
.\gradlew.bat :investigation-assistant-service:bootRun
```

It consumes the same Fraud Case projection topic as `case-search-service` under its own
consumer group, indexes safe synthetic evidence chunks into a dedicated OpenSearch vector
index, and performs genuine hybrid (BM25 + k-NN + RRF) retrieval. It generates no AI answers
in Cycle 6 Increment 6A; see
[the AI investigation assistant guide](docs/business/ai-investigation-assistant.md) for the
safe-field allowlist, complete configuration table, focused test commands, manual index cutover,
and current replay limitations.

Then run the deterministic simulator catalog in another window:

```powershell
.\gradlew.bat :transaction-simulator:run --args="--mode scenarios --run-id local-acceptance-001 --seed 42"
```

Use a clean, dedicated local environment for the stateful scenario expectations. The simulator
never clears infrastructure state. Reusing the explicit run ID intentionally exercises stored
authorization idempotency; using a new run ID creates new fraud velocity observations.

See [the transaction simulator guide](docs/testing/transaction-simulator.md) for modes,
configuration, scenario behavior, safe fixture aliases, reporting, and complete manual acceptance
steps. The [authorization and event acceptance guide](docs/testing/cycle-3-manual-acceptance.md)
shows how to inspect PostgreSQL, the outbox, and Kafka separately.
Case-consumer recovery operations are documented in
[the Kafka recovery runbook](docs/operations/case-management-kafka-recovery.md).
Fraud Case projection recovery is documented in
[the projection runbook](docs/operations/fraud-case-projection-recovery.md).

## Build

```powershell
.\gradlew.bat build
docker compose config
```

The business and API contracts are under [`docs/business`](docs/business).
