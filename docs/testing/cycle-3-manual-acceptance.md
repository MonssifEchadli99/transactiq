# Local Authorization and Event-Publication Acceptance

Run these commands from the repository root in PowerShell. Docker Desktop and Java 21 must be
available. All database credentials and request data below are synthetic local fixtures.

## Start PostgreSQL, Redis, and Kafka

```powershell
docker compose up -d postgres redis kafka
docker compose ps
```

The PostgreSQL compose service uses `postgres:18.4-alpine3.24` and the application's local datasource
defaults: database `transactiq_authorization`, username `transactiq_local`, password
`transactiq_local`, and host port `5433`. PostgreSQL continues to listen on port `5432` inside the
container.

Kafka uses the pinned official `apache/kafka:4.1.2` image in single-node KRaft mode and is bound to
`localhost:9092`. To use another host port, set `TRANSACTIQ_KAFKA_PORT` before starting Compose and
set the application bootstrap address to the same endpoint:

```powershell
$env:TRANSACTIQ_KAFKA_PORT = '19092'
$env:TRANSACTIQ_KAFKA_BOOTSTRAP_SERVERS = 'localhost:19092'
```

To use another local host port, set the same value in every PowerShell window that runs Compose or
the application. For example:

```powershell
$env:TRANSACTIQ_POSTGRES_PORT = '55433'
```

## Start fraud-engine

In a second PowerShell window:

```powershell
.\gradlew.bat :fraud-engine:bootRun
```

The local gRPC server listens on `localhost:9090` and uses the local Redis service. Plaintext is
for this documented local workflow only.

## Start authorization-service

In a third PowerShell window:

```powershell
.\gradlew.bat :authorization-service:bootRun
```

Liquibase creates the schema and inserts the documented synthetic accounts. The endpoint is
`http://localhost:8080/api/v1/authorizations`. The fraud client defaults to `localhost:9090` with
a two-second deadline. Override its endpoint with `TRANSACTIQ_FRAUD_GRPC_HOST` and
`TRANSACTIQ_FRAUD_GRPC_PORT` when needed.

Authorization responses do not wait for Kafka. The service writes the serialized v1 event to its
PostgreSQL outbox and the scheduled relay publishes it to
`transactiq.authorization.completed.v1` asynchronously.

## Send an approval

```powershell
$authorizationUri = 'http://localhost:8080/api/v1/authorizations'
$approval = @'
{
  "requestId": "60000000-0000-4000-8000-000000000001",
  "cardToken": "tok_A1B2C3D4",
  "merchantId": "merchant-standard",
  "merchantCategoryCode": "5411",
  "amount": 100.00,
  "currency": "EUR",
  "country": "DE",
  "channel": "ECOMMERCE",
  "transactionTime": "2026-07-20T10:15:30Z"
}
'@
Invoke-RestMethod -Method Post -Uri $authorizationUri -ContentType 'application/json' -Body $approval
```

Expected result: `APPROVED` with HTTP 200.

## Send an insufficient-funds decline

```powershell
$insufficientFunds = @'
{
  "requestId": "60000000-0000-4000-8000-000000000002",
  "cardToken": "tok_insufficient01",
  "merchantId": "merchant-standard",
  "merchantCategoryCode": "5411",
  "amount": 10.00,
  "currency": "EUR",
  "country": "DE",
  "channel": "ECOMMERCE",
  "transactionTime": "2026-07-20T10:16:30Z"
}
'@
Invoke-RestMethod -Method Post -Uri $authorizationUri -ContentType 'application/json' -Body $insufficientFunds
```

Expected result: `DECLINED` with `INSUFFICIENT_FUNDS` and HTTP 200.

## Repeat the identical approval

```powershell
Invoke-RestMethod -Method Post -Uri $authorizationUri -ContentType 'application/json' -Body $approval
```

Expected result: the original approval is returned without another reservation or balance change.

## Produce REQUEST_ID_CONFLICT

This reuses the approval's `requestId` with a different amount:

```powershell
$conflict = @'
{
  "requestId": "60000000-0000-4000-8000-000000000001",
  "cardToken": "tok_A1B2C3D4",
  "merchantId": "merchant-standard",
  "merchantCategoryCode": "5411",
  "amount": 125.00,
  "currency": "EUR",
  "country": "DE",
  "channel": "ECOMMERCE",
  "transactionTime": "2026-07-20T10:15:30Z"
}
'@
$conflict | curl.exe -sS -i -X POST $authorizationUri -H "Content-Type: application/json" --data-binary '@-'
```

Expected result: HTTP 409 and `{"code":"REQUEST_ID_CONFLICT"}`.

## Inspect PostgreSQL state

Use the container's `psql` client:

```powershell
'SELECT card_token, currency, posted_balance, reserved_amount FROM "authorization".card_accounts ORDER BY card_token;' |
docker compose exec -T postgres psql -U transactiq_local -d transactiq_authorization

'SELECT request_id, decision, decline_reason, fraud_assessment, risk_score, non_fraud_check_result FROM "authorization".authorization_ledger ORDER BY created_at;' |
docker compose exec -T postgres psql -U transactiq_local -d transactiq_authorization

'SELECT request_id, match_order, rule_code, severity, score_contribution, evidence FROM "authorization".fraud_rule_matches ORDER BY request_id, match_order;' |
docker compose exec -T postgres psql -U transactiq_local -d transactiq_authorization

'SELECT reservation_id, request_id, account_id, amount, currency, status FROM "authorization".balance_reservations ORDER BY created_at;' |
docker compose exec -T postgres psql -U transactiq_local -d transactiq_authorization

'SELECT request_id, status, created_at, completed_at FROM "authorization".authorization_requests ORDER BY created_at;' |
docker compose exec -T postgres psql -U transactiq_local -d transactiq_authorization

'SELECT event_id, request_id, event_type, event_version, partition_key, publication_state, attempt_count, next_attempt_at, published_at, last_error_code FROM "authorization".authorization_outbox ORDER BY created_at;' |
docker compose exec -T postgres psql -U transactiq_local -d transactiq_authorization
```

After Kafka acknowledges the event, the outbox row should be `PUBLISHED`. If Kafka is temporarily
stopped, the HTTP authorization still completes and the row remains retryable; restart Kafka and
observe the same row become `PUBLISHED` after the bounded backoff. The raw card token must not
appear in the outbox. The partition key is its lowercase SHA-256 fingerprint.

To inspect the local topic value and key with the Kafka container tools:

```powershell
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh `
  --bootstrap-server localhost:19092 `
  --topic transactiq.authorization.completed.v1 `
  --from-beginning `
  --property print.key=true `
  --max-messages 1
```

The value is binary Protobuf, so the console rendering is not human-readable. The automated Kafka
integration test parses and verifies it. Delivery is at-least-once; Case Management persistently
deduplicates using `eventId`. See
[Cycle 5 fraud-case acceptance](cycle-5-manual-acceptance.md). No DLT or DLQ exists yet.

For DBeaver, connect to `localhost:5433` with the database, username, and password shown above, then
browse the `authorization` schema.

## Stop local data services

This retains the local database volume:

```powershell
docker compose down
```

Optional destructive cleanup—this deletes the local PostgreSQL volume and all manual-test data:

```powershell
docker compose down --volumes
```

Run the volume-deleting command only when a completely fresh local database is intended.
