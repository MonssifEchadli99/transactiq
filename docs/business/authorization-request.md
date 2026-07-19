# Authorization Request

## Merchant/acquirer simulator input

The V1 authorization request contains:

- `requestId`: Required UUID used later for duplicate detection.
- `cardToken`: Required synthetic token matching `tok_` followed by 8–60
  alphanumeric characters; never a real card number.
- `merchantId`: Required, non-blank text with at most 64 characters.
- `merchantCategoryCode`: Required, non-blank merchant category code (MCC) containing exactly four digits.
- `amount`: Required positive decimal with at most 12 integer digits and two fractional digits.
- `currency`: Required, non-blank value containing exactly three uppercase letters.
- `country`: Required, non-blank value containing exactly two uppercase letters.
- `channel`: Required value of `ECOMMERCE` or `POINT_OF_SALE`.
- `transactionTime`: Required timestamp with no past or future restriction.

Validation does not normalize invalid input.

## Successful HTTP authorization

`POST /api/v1/authorizations` consumes `application/json`. A completed approval or business
decline returns `200 OK` and echoes the request identifier.

An approved response contains `requestId` and `decision: APPROVED`. A declined response also
contains `declineReason`, with one of `INSUFFICIENT_FUNDS`, `FRAUD_REVIEW_REQUIRED`, or
`HIGH_FRAUD_RISK`. The successful response does not expose assessment details, scores, evidence,
case data, or ledger data.

Validation failures return `400 Bad Request` with code `INVALID_AUTHORIZATION_REQUEST` and all
field errors sorted by field and message. Malformed JSON or typed representations return `400 Bad
Request` with code `MALFORMED_AUTHORIZATION_REQUEST`. Unexpected technical failures return `500
Internal Server Error` with code `AUTHORIZATION_PROCESSING_ERROR`; they are not business decision
values. Malformed and technical responses omit field errors and never expose internal exception or
request details.

## Trusted internal information

The merchant must not supply:

- Card status.
- Available balance.
- Previous transaction history.
- Velocity counters.
- Customer or card risk profile.
- Fraud score.
- Triggered fraud rules.

This information is trusted platform context used when assessing and authorizing the request.

## Generated fraud result

Fraud processing generates:

- Fraud assessment.
- Risk score.
- Triggered-rule evidence.

## Generated authorization result

Authorization processing generates:

- Final decision.
- Decision reason.
- Associated fraud-case identifier when applicable.

## Separation of responsibilities

Merchant input describes the transaction being attempted. Trusted internal information reflects platform-held facts that the merchant cannot assert or alter. Calculated results are produced from the request and trusted context. Keeping these groups separate preserves trust in the assessment and final decision.

## Synthetic example

Authorization request `d5e75b60-a263-4f76-b5d0-a35f1a09bc67` uses synthetic card token
`tok_A1B2C3D4` at merchant `merchant-123`, with MCC `5732`, for EUR 1,200.00 in `DE`
through the `ECOMMERCE` channel. The merchant simulator also supplies the transaction time. The
platform obtains trusted internal information and generates the fraud and authorization results
separately.

## Demo-only deterministic fixtures

The in-memory adapters recognize these synthetic identifiers for local demonstrations and tests:

- `merchant-review` produces `REVIEW`.
- `merchant-high-risk` produces `HIGH_RISK`.
- Any other valid merchant identifier produces `CLEAR`.
- `tok_insufficient01` produces `INSUFFICIENT_FUNDS`.
- Any other valid card token produces `PASSED`.

These identifiers are demo-only fixtures, not production data, configuration, or fraud rules.
