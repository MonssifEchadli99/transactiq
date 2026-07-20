# Authorization Decision Policy

## Purpose

Fraud assessment and authorization decision are separate concepts. A fraud assessment describes the transaction's fraud risk; the authorization decision determines whether the transaction is approved or declined.

## Fraud assessments

- `CLEAR`
- `REVIEW`
- `HIGH_RISK`

The fraud engine produces the fraud assessment, risk score, and evidence from triggered rules.

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

- `CLEAR`: Approve only when all non-fraud authorization checks pass. Do not open a fraud case.
- `REVIEW`: Decline with reason `FRAUD_REVIEW_REQUIRED` and automatically open a fraud case.
- `HIGH_RISK`: Decline with reason `HIGH_FRAUD_RISK` and automatically open a fraud case.

A non-fraud failure can decline a `CLEAR` transaction without opening a fraud case.

When a non-fraud failure coexists with `REVIEW` or `HIGH_RISK`, the non-fraud failure remains the primary decline reason, while a fraud case is still required.

## Ledger responsibility

The ledger records the request, fraud assessment, final decision, reason, timestamps, and associated case identifier when applicable. It records the outcome but does not make the decision.

The live persistent completion component also records the exact non-fraud check result. An
approved outcome creates one active reservation and increases the account's reserved amount by the
authorization amount. A declined outcome creates no reservation and does not change the balance.
All completion writes occur in one transaction for an existing pending request.

## Examples

### REVIEW transaction

A synthetic EUR 75 transaction receives a `REVIEW` fraud assessment. The authorization policy returns `DECLINED` with reason `FRAUD_REVIEW_REQUIRED`, and a fraud case is automatically opened. The ledger records the assessment, decision, reason, timestamps, and case identifier.

### CLEAR transaction with insufficient funds

A synthetic EUR 120 transaction receives a `CLEAR` fraud assessment but fails the available-funds check. The authorization policy returns `DECLINED` because of insufficient funds. No fraud case is opened, and the ledger records the assessment, decision, reason, and timestamps.

## Future evolution

A later policy may consider risk score, amount, available balance, merchant category, country, velocity, channel, authentication result, and customer risk profile when determining the final authorization decision.
