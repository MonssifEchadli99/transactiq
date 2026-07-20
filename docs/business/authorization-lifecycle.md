# Authorization Lifecycle

## V1 lifecycle

The live HTTP workflow uses the database-backed idempotency claim and atomic authorization
completion components. The deterministic in-memory fraud adapter remains active, while balances,
reservations, request status, and ledger outcomes are persisted in PostgreSQL.

For an authorization request that passes HTTP validation:

1. The merchant simulator submits an authorization request.
2. The platform validates the request.
3. The platform claims `requestId` and stores the canonical request as `PENDING`.
4. A matching completed claim returns its stored outcome without repeating fraud assessment,
   balance checks, reservations, or ledger writes.
5. A matching in-progress claim returns HTTP `202` with status `PENDING`; a different payload for
   the same identifier returns HTTP `409` with `REQUEST_ID_CONFLICT`.
6. For a newly claimed request, the fraud engine produces a fraud assessment.
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
12. The ledger records the request, assessment, checks, outcome, reason, and timestamps; approvals
    also reserve funds.
13. The request becomes `COMPLETED`, and the merchant simulator receives HTTP `200`.

An unexpected fraud or completion failure also releases the claim only while it remains `PENDING`
and preserves the generic HTTP `500` response.

## Atomic persistent completion component

For an existing `PENDING` idempotency claim, the live completion component locks the card
account, evaluates available funds, applies the authorization policy, and persists the outcome in
one PostgreSQL transaction.

- An approval increases `reserved_amount`, creates one `ACTIVE` balance reservation, records the
  ledger entry, and changes the request status from `PENDING` to `COMPLETED`.
- A decline records the ledger entry and changes the request status to `COMPLETED`, without a
  reservation or balance change.
- A technical persistence failure rolls back every balance, reservation, ledger, and request-status
  change made by that completion attempt.

The card-account row lock serializes concurrent balance decisions for the same account. A later
transaction evaluates the available balance after an earlier reservation commits.

## Status transitions

The persistent request record currently uses `PENDING` to `COMPLETED`; the ledger stores the final
`APPROVED` or `DECLINED` decision. The broader V1 lifecycle describes these business outcomes as:

- `PENDING` → `APPROVED`
- `PENDING` → `DECLINED`
- `PENDING` → `TIMED_OUT`

## Examples

- A synthetic transaction assessed as `CLEAR`, with sufficient funds and a valid card, becomes `APPROVED`.
- A synthetic transaction assessed as `REVIEW` becomes `DECLINED` with reason `FRAUD_REVIEW_REQUIRED`, and a fraud case is opened.
- A synthetic transaction assessed as `CLEAR` but with insufficient funds becomes `DECLINED` with reason `INSUFFICIENT_FUNDS`; no fraud case is opened.
- A synthetic transaction whose processing exceeds its deadline becomes `TIMED_OUT` with reason `PROCESSING_TIMEOUT`; no fraud case is automatically opened.

## Timeout boundaries

`TIMED_OUT` is a technical outcome, not a fraud decline. A timed-out request has no completed authorization decision. The caller may retry according to a future retry policy, but retry and duplicate-request behavior are intentionally undefined in V1. The timeout duration is also not yet defined.
