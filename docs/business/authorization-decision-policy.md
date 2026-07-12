# Authorization Decision Policy

## Purpose

Fraud assessment and authorization decision are separate concepts. A fraud assessment describes the transaction's fraud risk; the authorization decision determines whether the transaction is approved or declined.

## Fraud assessments

- `CLEAR`
- `REVIEW`
- `HIGH_RISK`

The fraud engine produces the fraud assessment, risk score, and evidence from triggered rules.

## Authorization decisions

- `APPROVED`
- `DECLINED`

The authorization policy produces the final authorization decision.

## V1 decision policy

- `CLEAR`: Approve only when all non-fraud authorization checks pass. Do not open a fraud case.
- `REVIEW`: Decline with reason `FRAUD_REVIEW_REQUIRED` and automatically open a fraud case.
- `HIGH_RISK`: Decline with reason `HIGH_FRAUD_RISK` and automatically open a fraud case.

A non-fraud failure can decline a `CLEAR` transaction without opening a fraud case.

## Ledger responsibility

The ledger records the request, fraud assessment, final decision, reason, timestamps, and associated case identifier when applicable. It records the outcome but does not make the decision.

## Examples

### REVIEW transaction

A synthetic EUR 75 transaction receives a `REVIEW` fraud assessment. The authorization policy returns `DECLINED` with reason `FRAUD_REVIEW_REQUIRED`, and a fraud case is automatically opened. The ledger records the assessment, decision, reason, timestamps, and case identifier.

### CLEAR transaction with insufficient funds

A synthetic EUR 120 transaction receives a `CLEAR` fraud assessment but fails the available-funds check. The authorization policy returns `DECLINED` because of insufficient funds. No fraud case is opened, and the ledger records the assessment, decision, reason, and timestamps.

## Future evolution

A later policy may consider risk score, amount, available balance, merchant category, country, velocity, channel, authentication result, and customer risk profile when determining the final authorization decision.
