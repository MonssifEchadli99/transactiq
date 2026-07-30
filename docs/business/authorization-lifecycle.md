# Authorization Lifecycle

## V1 lifecycle

The live HTTP workflow uses the database-backed idempotency claim, the synchronous fraud-engine
gRPC boundary, and atomic authorization completion. Balances, reservations, request status,
authorization outcomes, fraud assessments, total scores, and every scored fraud-rule match are
persisted in PostgreSQL.
The deterministic fraud adapter is retained only as test support.

For an authorization request that passes HTTP validation:

1. The merchant simulator submits an authorization request.
2. The platform validates the request.
3. The platform claims `requestId` and stores the canonical request as `PENDING`.
4. A matching completed claim returns its stored outcome without repeating fraud assessment,
   balance checks, reservations, or ledger writes.
5. A matching in-progress claim returns HTTP `202` with status `PENDING`; a different payload for
   the same identifier returns HTTP `409` with `REQUEST_ID_CONFLICT`.
6. Only for a newly claimed request, authorization-service calls fraud-engine exactly once, with a
   positive deadline and no client retry. The engine returns the assessment, score, and all scored
   matches.
7. Inside the completion transaction, the platform verifies that the request currency is EUR. A
   non-EUR request is rejected as `UNSUPPORTED_CURRENCY` without looking up the card token.
8. For a EUR request, the platform locks and looks up the synthetic card account. An unknown token
   is rejected as `UNKNOWN_CARD_TOKEN`.
9. The platform verifies the account currency and evaluates available funds as
   `posted_balance - reserved_amount`.
10. A pre-authorization rejection releases the still-`PENDING` claim, creates no authorization
    decision or ledger entry, and preserves the existing HTTP `400` contract.
11. The authorization policy combines the fraud assessment and non-fraud check result to produce
    the final outcome.
12. The ledger records the request, assessment, total score, every matched
    code/severity/evidence/contribution tuple, checks, outcome, reason, and timestamps; approvals
    also reserve funds.
13. A versioned authorization-completed event is serialized and inserted into the outbox in the
    same transaction. Its `caseRequired` value is false for `CLEAR` and true for `REVIEW` or
    `HIGH_RISK`, regardless of decision or decline reason.
14. The request becomes `COMPLETED`, and the merchant simulator receives HTTP `200` without waiting
    for Kafka. An independent relay later publishes the stored bytes.

An unavailable/deadline/corrupt/unexpected fraud response, including an absent or inconsistent
score/contribution, or a completion storage failure is a
technical failure, not a fraud result. It releases the newly acquired claim while it remains
`PENDING`, creates no ledger entry or reservation, and returns only the stable generic HTTP `500`
code. Fraud-engine `FAILED_PRECONDITION` maps to HTTP `409 REQUEST_ID_CONFLICT` and also releases the
new claim. `INVALID_ARGUMENT` after HTTP validation is an integration invariant failure and maps to
the same generic HTTP `500` response.

## Atomic persistent completion component

For an existing `PENDING` idempotency claim, the live completion component locks the card
account, evaluates available funds, applies the authorization policy, and persists the outcome in
one PostgreSQL transaction.

- An approval increases `reserved_amount`, creates one `ACTIVE` balance reservation, records the
  ledger entry, and changes the request status from `PENDING` to `COMPLETED`.
- A decline records the ledger entry and changes the request status to `COMPLETED`, without a
  reservation or balance change.
- A technical persistence or event-creation failure rolls back every balance, reservation, ledger,
  outbox, and request-status change made by that completion attempt.

Kafka unavailability occurs after this transaction boundary. It does not undo a completed
authorization: the outbox event stays retryable until the asynchronous relay receives a broker
acknowledgement. Delivery is at-least-once, so future consumers must deduplicate with the stable
`eventId`. See [authorization-completed-event.md](authorization-completed-event.md).

The card-account row lock serializes concurrent balance decisions for the same account. A later
transaction evaluates the available balance after an earlier reservation commits.

## Status transitions

The persistent request record currently uses `PENDING` to `COMPLETED`; the ledger stores the final
`APPROVED` or `DECLINED` decision:

- `PENDING` → `APPROVED`
- `PENDING` → `DECLINED`

A technical failure does not add a business status. It deletes the still-pending claim so a retry
can start a new execution.

## Examples

- A synthetic transaction assessed as `CLEAR`, with sufficient funds and a valid card, becomes `APPROVED`.
- A synthetic transaction assessed as `REVIEW`, with sufficient funds, becomes `APPROVED`; its
  score and matches remain persisted for future case handling.
- A synthetic transaction assessed as `HIGH_RISK`, with sufficient funds, becomes `DECLINED` with
  reason `HIGH_FRAUD_RISK`.
- A synthetic transaction assessed as `CLEAR` but with insufficient funds becomes `DECLINED` with reason `INSUFFICIENT_FUNDS`; no fraud case is opened.
- A synthetic transaction whose fraud call exceeds its deadline receives the stable technical HTTP
  `500` response, with no completed authorization decision.

## Timeout boundaries

The local default fraud deadline is two seconds and is configurable. A timed-out request has no
completed authorization decision; its pending claim is released, so a caller retry can acquire the
claim and call fraud-engine again. If fraud-engine processed the first attempt but its response was
lost, the same `requestId` and payload use fraud-engine deduplication and do not increment velocity
again.

An identical retry after completion returns the original stored score and contributions without a
new fraud RPC or recomputation from current scoring configuration.
