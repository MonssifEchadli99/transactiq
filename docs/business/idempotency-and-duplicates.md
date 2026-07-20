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
- The same canonical payload returns `COMPLETED` with the stored authorization outcome after
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

Completion requires an existing `PENDING` claim. Inside one database transaction, the component
locks the card account, performs the available-funds check, applies the authorization policy, and
persists the exact fraud assessment, non-fraud result, decision, and decline reason. Approvals also
reserve the authorized amount; declines leave the balance unchanged. The request becomes
`COMPLETED` only after all expected writes succeed, and any technical failure rolls back the whole
attempt.

Account-row locking prevents concurrent requests from approving against the same available funds.
The completion component is invoked only after the live orchestrator receives `CLAIMED`.

## Live processing results

- `COMPLETED` returns the stored approval or decline as HTTP `200` without repeating fraud,
  balance checks, ledger writes, or reservations.
- `PENDING` returns HTTP `202` with the request identifier and status `PENDING`; it is an expected
  workflow result, not an exception.
- `CONFLICT` returns HTTP `409` with code `REQUEST_ID_CONFLICT`; it is an expected workflow result,
  not an exception.
- `CLAIMED` runs fraud assessment and atomic completion.

If pre-authorization validation inside completion or a technical processing step fails, the
orchestrator deletes the claim only when it is still `PENDING`, then propagates the original
failure. This preserves the existing HTTP `400` or generic `500` response and permits a later retry
to claim the identifier again.

## Same requestId with identical request data

- Process the authorization only once.
- If the original request is `PENDING`, do not start another authorization workflow.
- If the original request is completed, return its recorded outcome.
- Never apply a balance operation twice.
- Never create duplicate fraud cases.

Example: Synthetic request `AUTH-10001` was declined and associated with fraud case `CASE-9001`. An identical duplicate returns the same decline and `CASE-9001`; it does not repeat processing or create another case.

## Same requestId with different request data

- Reject the later request with `REQUEST_ID_CONFLICT`.
- Do not perform fraud assessment.
- Do not create a fraud case.

`REJECTED` occurs before the request is accepted into the normal authorization lifecycle. It is not an additional transition from `PENDING`.

Example: Synthetic request `AUTH-10001` is first submitted for EUR 75 and later reused for EUR 200. The later request is rejected with `REQUEST_ID_CONFLICT` before authorization processing begins.

## Late result after timeout

- Keep the merchant-facing `TIMED_OUT` outcome unchanged.
- Do not convert it later into `APPROVED` or `DECLINED`.
- Record the late result for audit and observability.
- Do not automatically create a fraud case from the late result in V1.

Automated retry behavior remains outside V1 and will be defined separately.
