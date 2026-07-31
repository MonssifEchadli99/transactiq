# Fraud Case Management

## Case creation boundary

A fraud case is an immutable investigation snapshot created from a completed-authorization event.
It never changes the authorization decision, decline reason, balance, reservation, fraud
assessment, or evidence.

Case Management owns its PostgreSQL `fraud_case` schema and Liquibase migrations. It consumes only
the versioned event contract and never reads authorization-service tables.

## Creation policy

- `CLEAR` requires `caseRequired=false`; the valid event is acknowledged without a case.
- `REVIEW` and `HIGH_RISK` require `caseRequired=true`; one case is created.
- Any inconsistent combination is an invalid contract and is not acknowledged.
- A new case has generated `caseId`, status `NEW`, version `0`, no assignee, and matching creation
  and update timestamps.
- The defined lifecycle is `NEW` → `IN_REVIEW` → `RESOLVED`; Increment 3 implements only
  `NEW` → `IN_REVIEW` through self-claim.
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

## Analyst queue, details, and self-claim

The queue is ordered by `createdAt ASC, caseId ASC` and uses an opaque keyset cursor. Status and
assignment filters are supported. Pagination is deterministic for an unchanged matching set, but
separate HTTP requests do not share snapshot isolation; a concurrent claim can move a case into or
out of a filter between pages.

Case details expose the complete immutable authorization/fraud snapshot, pseudonymous card
fingerprint, and rule matches in stored order. They never expose a raw card token or Kafka payload.

The only mutable operation is self-claim. A successful claim atomically assigns the analyst,
changes `NEW` to `IN_REVIEW`, increments the optimistic version, updates `updatedAt`, and appends
one immutable `CLAIMED` lifecycle event. The same analyst can safely retry with the pre-claim
version without another mutation or audit event. Another analyst receives a conflict.

`X-Analyst-Id` is caller-supplied development identity. It is not authentication, authorization,
or evidence of a real analyst account.

## Explicit exclusions

This increment has no resolution, reopen, release, reassignment, notes, history endpoint, real
identity/security, gateway route, lifecycle Kafka event, transaction action, refund, case grouping,
priority, OpenSearch projection, DLT replay tooling, or AI/RAG behavior.
