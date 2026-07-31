# Idempotency and Duplicate Requests

> **Implementation note:** The live HTTP authorization workflow uses the database-backed claim and
> atomic completion components. The production ledger is PostgreSQL-backed; the in-memory ledger is
> retained only for focused tests.

## Database-backed claim component

The live claim component uses `requestId` as the idempotency key. The first claim atomically
stores the complete canonical request payload and a deterministic SHA-256 fingerprint in a
`PENDING` authorization-request row, then returns `CLAIMED`.

For a repeated `requestId`:

- The same canonical payload returns `PENDING` while processing is incomplete.
- The same canonical payload returns `COMPLETED` with the stored authorization outcome, original
  fraud assessment, original score, and all ordered matched-rule details and contributions after
  completion.
- A different canonical payload returns `CONFLICT`.

Canonical equality includes every authorization-command field. JSON property order and formatting
do not affect the fingerprint. Numerically equal decimal amounts have the same representation, and
timestamps are compared as instants. A technical failure may release a claim by deleting it only
while it remains `PENDING`.

PostgreSQL request-key uniqueness and transactions arbitrate concurrent first claims. Two
simultaneous claims for the same new request produce one `CLAIMED` result and one `PENDING` result;
the component does not use JVM-only locking.

## Atomic persistent completion

Completion requires an existing `PENDING` claim. After the synchronous fraud call, one database
transaction locks the card account, performs the available-funds check, applies the authorization
policy, and persists the exact fraud assessment and score, every ordered
code/severity/evidence/contribution match, non-fraud result, decision, and decline reason. Approvals
also reserve the authorized amount;
declines leave the balance unchanged. The request becomes `COMPLETED` only after all expected
writes succeed, and any technical failure rolls back the whole attempt.

That transaction also inserts exactly one immutable serialized authorization-completed event,
uniquely constrained by request and event type. A completed identical retry neither rebuilds nor
updates the original event, its identifier, or its occurrence time. Pending and conflicting
duplicates create no event.

Account-row locking prevents concurrent requests from approving against the same available funds.
The completion component is invoked only after the live orchestrator receives `CLAIMED`.

## Live processing results

- `COMPLETED` returns the stored approval or decline as HTTP `200` without repeating fraud,
  balance checks, ledger writes, or reservations.
- `PENDING` returns HTTP `202` with the request identifier and status `PENDING`; it is an expected
  workflow result, not an exception.
- `CONFLICT` returns HTTP `409` with code `REQUEST_ID_CONFLICT`; it is an expected workflow result,
  not an exception.
- `CLAIMED` performs exactly one fraud RPC with no client retry, then runs atomic completion.

If pre-authorization validation inside completion or a technical processing step fails, the
orchestrator deletes the claim only when it is still `PENDING`, then propagates the original
failure. This preserves the existing HTTP `400` or generic `500` response and permits a later retry
to claim the identifier again.

If the engine processed an attempt but the response was lost, authorization-service releases its
pending claim. A later retry makes a new single RPC with the same `requestId`; fraud-engine returns
its deduplicated snapshot and does not add another velocity observation. Engine
`FAILED_PRECONDITION` becomes the existing conflict response and releases the authorization claim.

## Same requestId with identical request data

- Process the authorization only once.
- If the original request is `PENDING`, do not start another authorization workflow.
- If the original request is completed, return its recorded outcome.
- Never apply a balance operation twice.
- Do not call fraud-engine again.
- Do not create or republish a second outbox event.

Example: synthetic request `d5e75b60-a263-4f76-b5d0-a35f1a09bc67` completed with a `REVIEW`
assessment and an approval. An identical duplicate returns the same approval and retains the
original assessment, score, and matched-rule contributions without another fraud call,
recomputation, or reservation.

## Same requestId with different request data

- Reject the later request with `REQUEST_ID_CONFLICT`.
- Do not perform fraud assessment.

`REJECTED` occurs before the request is accepted into the normal authorization lifecycle. It is not an additional transition from `PENDING`.

Example: Synthetic request `AUTH-10001` is first submitted for EUR 75 and later reused for EUR 200. The later request is rejected with `REQUEST_ID_CONFLICT` before authorization processing begins.

## Lost or late fraud response

A fraud deadline is a technical HTTP `500`, not a stored authorization decision. No ledger or
reservation is written, and the pending authorization claim is released. If a fraud response
arrives after the client deadline, it cannot complete the released authorization execution. A
caller retry starts a newly claimed execution while fraud-engine deduplication prevents the same
request from adding another velocity observation.

## Case-consumer duplicate delivery

Authorization-request idempotency and Kafka-consumer idempotency are separate boundaries. Case
Management hashes the exact received Kafka value bytes before parsing. Its PostgreSQL transaction
uses unique constraints on both `source_event_id` and `request_id` as the final concurrency
guarantee.

- The same `eventId` and byte hash is a successful no-op.
- The same `eventId` with another hash is a contract conflict.
- The same `requestId` with another `eventId` is a contract conflict.
- Concurrent delivery of identical bytes creates exactly one case.

The Kafka offset is acknowledged after successful database completion or after the failed source
record has been acknowledged by the recovery DLT. A DLT publication failure does not advance the
source offset. Repeated DLT publications are possible; `source-topic:partition:offset` is the stable
recovery identifier for downstream deduplication.

## Analyst claim retries and concurrency

Fraud Case claim commands use an optimistic `version`. The first claim is a database compare-and-set
over case ID, `NEW`, unassigned state, and the expected version. Concurrent claims therefore have
one winner. The mutation and its single append-only `CLAIMED` lifecycle event commit together.

A retry by the owning analyst returns the stored `IN_REVIEW` state even if it carries the original
pre-claim version; it does not update timestamps, increment the version, or append another event.
A different analyst receives `CASE_ALREADY_ASSIGNED`. A stale version on an otherwise claimable
case receives `CASE_VERSION_CONFLICT`.

Duplicate Kafka delivery remains separate from HTTP command idempotency: it cannot reset status,
assignee, version, or `updatedAt`, and cannot create a lifecycle event.
