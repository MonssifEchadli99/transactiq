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
- `investigation-assistant-service`: Kafka-to-OpenSearch safe evidence indexer, hybrid
  (BM25 + k-NN + RRF) retrieval, structured OpenAI generation, and two read-only MCP investigation
  tools through Spring AI. Its evidence and grounded-answer capabilities are advisory only.
- `observability-support`: shared HTTP correlation-ID and bounded Micrometer instrumentation used
  by the deployable Spring services.
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
key to use the read-only REST endpoints or the synchronous Streamable HTTP MCP server at
`http://localhost:8080/mcp`. The default provider path uses
`OPENAI_API_KEY`; explicitly configured embedding- or chat-specific keys take precedence for
their respective calls and must themselves be nonblank. `OPENAI_LOG` must be absent or set to
`off` so SDK diagnostics cannot bypass the service's logging controls. Chat defaults to
`gpt-4.1-mini` with a `10s` timeout; both remain environment-configurable:

```powershell
$env:OPENAI_API_KEY = "<your-key>"
$env:TRANSACTIQ_CHAT_MODEL = "gpt-4.1-mini"
$env:TRANSACTIQ_CHAT_TIMEOUT = "10s"
.\gradlew.bat :investigation-assistant-service:bootRun
```

It consumes the same Fraud Case projection topic as `case-search-service` under its own
consumer group, indexes safe synthetic evidence chunks into a dedicated OpenSearch vector
index, performs genuine hybrid (BM25 + k-NN + RRF) retrieval, and validates every generated
finding against the retrieved source allowlist. Answers never mutate cases and return
`INSUFFICIENT_EVIDENCE` when the available context cannot ground a factual finding; see
[the AI investigation assistant guide](docs/business/ai-investigation-assistant.md) for the
REST and MCP contracts, citation rules, safe-field allowlist, client configuration, offline
evaluation, manual index cutover, and current limitations. The MCP server exposes exactly
`retrieve_fraud_case_evidence` and `answer_fraud_investigation_question`; both reuse the existing
in-process application services and provide no case-mutation capability. Cycle 6C adds no MCP
authentication, so this endpoint is for controlled local use and must not be exposed to an
untrusted network.

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

## Cloud deployment blueprint

Cycle 7-lite adds a validation-first GCP blueprint without deploying infrastructure. Terraform in
`infra/environments/dev` describes an immutable Artifact Registry, five dedicated Cloud Run
services and service accounts, private networking, Cloud SQL, Memorystore, empty Secret Manager
containers, and native Cloud Run error/latency alerts. Kafka and OpenSearch are explicit external
managed-service inputs; the blueprint does not pretend to provision them.

Pull requests and `main` run the Java 21 build, validate all five service images, and validate
Terraform. The manual deployment workflow uses GitHub OIDC/Workload Identity Federation and
commit-SHA image tags. It always produces a plan before an apply can cross the protected
`transactiq-dev` environment; missing repository configuration keeps deployment disabled. No
service-account JSON key is used.

The Spring applications expose only health, liveness, readiness, info, and Prometheus management
endpoints, with health details hidden. Logs default to structured JSON, safe request IDs propagate
through `X-Request-Id`, and bounded metrics cover authorization, fraud, case-event, and AI
investigation outcomes without using transaction data, evidence, or analyst content as labels.

See [the GCP deployment blueprint](docs/operations/gcp-deployment-blueprint.md) for the Mermaid
deployment diagram, complete service-to-dependency map, CI/CD flow, configuration and secret
strategy, prerequisites, validation commands, cost-conscious defaults, and current limitations.

## Build

```powershell
.\gradlew.bat build
docker compose config --quiet
terraform fmt -check -recursive infra
terraform -chdir=infra/environments/dev init -backend=false -input=false
terraform -chdir=infra/environments/dev validate
```

The business and API contracts are under [`docs/business`](docs/business).
