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
  bounded failure classification, and a same-partition recovery DLT.
- `fraud-contract`: versioned fraud-assessment gRPC contract.
- `fraud-engine`: deterministic synthetic stateless and velocity fraud rules backed by Redis.
- `event-contract`: versioned authorization-completed Protobuf contract.
- `transaction-simulator`: standalone Kotlin/JVM CLI for scenarios and controlled load.

## Local run

Java 21 and Docker Desktop are required. From PowerShell at the repository root:

```powershell
docker compose up -d postgres case-postgres redis kafka
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

## Build

```powershell
.\gradlew.bat build
docker compose config
```

The business and API contracts are under [`docs/business`](docs/business).
