# Cycle 3 Manual Acceptance

Run these commands from the repository root in PowerShell. Docker Desktop and Java 21 must be
available. All database credentials and request data below are synthetic local fixtures.

## Start PostgreSQL

```powershell
docker compose up -d postgres
docker compose ps
```

The compose service uses `postgres:18.4-alpine3.24` and the application's local datasource
defaults: database `transactiq_authorization`, username `transactiq_local`, password
`transactiq_local`, and host port `5433`. PostgreSQL continues to listen on port `5432` inside the
container.

To use another local host port, set the same value in every PowerShell window that runs Compose or
the application. For example:

```powershell
$env:TRANSACTIQ_POSTGRES_PORT = '55433'
```

## Start authorization-service

In a second PowerShell window:

```powershell
.\gradlew.bat :authorization-service:bootRun
```

Liquibase creates the schema and inserts the documented synthetic accounts. The endpoint is
`http://localhost:8080/api/v1/authorizations`.

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

'SELECT request_id, decision, decline_reason, fraud_assessment, non_fraud_check_result FROM "authorization".authorization_ledger ORDER BY created_at;' |
docker compose exec -T postgres psql -U transactiq_local -d transactiq_authorization

'SELECT reservation_id, request_id, account_id, amount, currency, status FROM "authorization".balance_reservations ORDER BY created_at;' |
docker compose exec -T postgres psql -U transactiq_local -d transactiq_authorization

'SELECT request_id, status, created_at, completed_at FROM "authorization".authorization_requests ORDER BY created_at;' |
docker compose exec -T postgres psql -U transactiq_local -d transactiq_authorization
```

For DBeaver, connect to `localhost:5433` with the database, username, and password shown above, then
browse the `authorization` schema.

## Stop PostgreSQL

This retains the local database volume:

```powershell
docker compose down
```

Optional destructive cleanup—this deletes the local PostgreSQL volume and all manual-test data:

```powershell
docker compose down --volumes
```

Run the volume-deleting command only when a completely fresh local database is intended.
