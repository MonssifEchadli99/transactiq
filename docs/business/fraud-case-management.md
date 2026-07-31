# Fraud Case Management

## Increment 1 boundary

A fraud case is an immutable investigation snapshot created from a completed-authorization event.
It never changes the authorization decision, decline reason, balance, reservation, fraud
assessment, or evidence.

Case Management owns its PostgreSQL `fraud_case` schema and Liquibase migrations. It consumes only
the versioned event contract and never reads authorization-service tables.

## Creation policy

- `CLEAR` requires `caseRequired=false`; the valid event is acknowledged without a case.
- `REVIEW` and `HIGH_RISK` require `caseRequired=true`; one case is created.
- Any inconsistent combination is an invalid contract and is not acknowledged.
- A new case has generated `caseId`, status `NEW`, and no assignee.
- The defined lifecycle is `NEW` → `IN_REVIEW` → `RESOLVED`, but this increment implements no
  transition or assignment operation.
- One suspicious authorization produces one case. There is no grouping and no priority field.

The case preserves the complete normalized event snapshot: source identities and exact-byte hash,
transaction context, authorization outcome and primary decline reason, assessment, risk score,
timestamps, and every ordered fraud-rule match.

## Delivery identity and conflicts

The consumer calculates lowercase SHA-256 over the exact received Kafka value bytes. PostgreSQL
uniqueness on `source_event_id` and `request_id` arbitrates sequential and concurrent delivery.

- Same `eventId` and hash: successful no-op.
- Same `eventId` with another hash: contract conflict.
- Same `requestId` with another `eventId`: contract conflict.

Case and rule rows share one database transaction. The Kafka offset is acknowledged after a
successful transaction. Invalid contracts and identity conflicts go directly to the recovery DLT;
unexpected failures use five total processing attempts, and temporary database/resource failures
retry indefinitely with capped exponential backoff. A source offset is committed after, never
before, successful DLT publication. Replay after a crash remains safe.

The DLT keeps the original key/value bytes, partition, and non-recovery headers. It adds a stable
source-coordinate recovery ID, category, exception class, UTC recovery time, processing attempt,
consumer group, and payload SHA-256. It intentionally excludes exception messages and stack traces.

## Explicit exclusions

This increment has no HTTP API, gateway route, assignment operation, lifecycle transition,
resolution, notes, transaction action, refund, case grouping, priority, OpenSearch projection,
DLT replay tooling, or AI/RAG behavior.
