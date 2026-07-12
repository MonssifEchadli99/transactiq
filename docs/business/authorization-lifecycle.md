# Authorization Lifecycle

## V1 lifecycle

For a valid authorization request:

1. The merchant simulator submits an authorization request.
2. The platform validates and accepts the request for processing.
3. The accepted request enters `PENDING` status.
4. The fraud engine produces a fraud assessment, risk score, and triggered-rule evidence.
5. Non-fraud checks are evaluated, including available funds and card status.
6. The authorization policy produces the final outcome.
7. The ledger records the request, assessment, checks, outcome, reason, and timestamps.
8. The merchant simulator receives the result.

## Status transitions

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
