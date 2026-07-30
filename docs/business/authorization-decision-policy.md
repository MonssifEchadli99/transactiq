# Authorization Decision Policy

## Purpose

Fraud assessment and authorization decision are separate concepts. A fraud assessment describes the transaction's fraud risk; the authorization decision determines whether the transaction is approved or declined.

## Fraud assessments

- `CLEAR`
- `REVIEW`
- `HIGH_RISK`

The fraud engine produces the authoritative fraud assessment plus a synthetic informational risk
score and matched-rule evidence/contributions; see [fraud-assessment.md](fraud-assessment.md) for
the full contract. The score is not a probability and is not an authorization-policy input.

## Pre-authorization rejections

An unsupported request currency is rejected before card-token lookup. For EUR requests, an unknown
synthetic card token is rejected after lookup. These conditions return `UNSUPPORTED_CURRENCY` or
`UNKNOWN_CARD_TOKEN`; they are rejected before the authorization policy runs, are not
`AuthorizationDecision` values, and do not create a ledger entry.

The live workflow has already claimed the request and run fraud assessment before atomic
completion reaches these checks. A rejection releases the claim while it remains `PENDING`.

## Authorization decisions

- `APPROVED`
- `DECLINED`

The authorization policy produces the final authorization decision.

## V1 decision policy

- `CLEAR`: Approve only when all non-fraud authorization checks pass.
- `REVIEW`: Do not automatically decline. Preserve the assessment and all matches for asynchronous
  case handling, and approve when all non-fraud authorization checks pass.
- `HIGH_RISK`: Activate the fraud-decline condition. When non-fraud checks pass, decline with reason
  `HIGH_FRAUD_RISK`.

A non-fraud failure can decline a `CLEAR` transaction without opening a fraud case.

Every newly claimed request continues through the non-fraud evaluation after fraud assessment.
When insufficient funds coexist with `REVIEW` or `HIGH_RISK`, `INSUFFICIENT_FUNDS` remains the
authoritative decline reason. The resulting completed event still creates a fraud case.

The authorization-completed event separately signals case work with `caseRequired=false`
for `CLEAR` and `caseRequired=true` for `REVIEW` or `HIGH_RISK`. That signal is independent of the
decision and decline reason; Case Management validates and consumes it asynchronously.

## Ledger responsibility

The ledger records the request, fraud assessment, total risk score, every matched rule's code,
severity, evidence, and individual contribution, plus the final decision, reason, and timestamps.
It records the outcome but does not make the decision.

The live persistent completion component also records the exact non-fraud check result. An
approved outcome creates one active reservation and increases the account's reserved amount by the
authorization amount. A declined outcome creates no reservation and does not change the balance.
All completion writes occur in one transaction for an existing pending request.

## Examples

### REVIEW transaction

A synthetic EUR 75 transaction receives a `REVIEW` fraud assessment with a score of 15 and passes
the available-funds check. The authorization policy returns `APPROVED`. The ledger preserves the
assessment, score, and supporting match, and the completed event asynchronously creates a `NEW`
case with the same snapshot.

### CLEAR transaction with insufficient funds

A synthetic EUR 120 transaction receives a `CLEAR` fraud assessment but fails the available-funds check. The authorization policy returns `DECLINED` because of insufficient funds. No fraud case is opened, and the ledger records the assessment, decision, reason, and timestamps.

### HIGH_RISK transaction with insufficient funds

A synthetic EUR 120 transaction receives a `HIGH_RISK` fraud assessment and also fails the
available-funds check. The final decision is `DECLINED` with reason `INSUFFICIENT_FUNDS`, while the
high-risk assessment and its matches remain stored.

## Case-management boundary

Case creation never changes the completed authorization. PostgreSQL owned by Case Management is
the source of truth for the immutable investigation snapshot. Assignment, status transitions,
resolution, search, and analyst APIs are outside Cycle 5 Increment 1.
