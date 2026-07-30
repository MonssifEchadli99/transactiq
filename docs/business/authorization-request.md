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
contains `declineReason`; the current connected policy produces `INSUFFICIENT_FUNDS` or
`HIGH_FRAUD_RISK`. A `REVIEW` assessment does not itself decline the transaction. The successful
response does not expose assessment details, scores, evidence, case data, or ledger data.

An identical retry while the original request is still processing returns `202 Accepted` with
`requestId` and `status: PENDING`. Reusing a request identifier with different canonical request
data returns `409 Conflict` with code `REQUEST_ID_CONFLICT`. These expected idempotency states are
returned explicitly rather than represented by exceptions.

Validation failures return `400 Bad Request` with code `INVALID_AUTHORIZATION_REQUEST` and all
field errors sorted by field and message. Malformed JSON or typed representations return `400 Bad
Request` with code `MALFORMED_AUTHORIZATION_REQUEST`.

After request validation, the platform first claims `requestId`. For a newly claimed request, fraud
assessment runs before atomic completion. Completion first checks the request currency because
Cycle 3 supports EUR only. A non-EUR request returns `400 Bad Request` with code
`UNSUPPORTED_CURRENCY`, without looking up the card token. For a EUR request, the platform locks and
looks up the synthetic card account by `cardToken`; an unknown token returns `400 Bad Request` with
code `UNKNOWN_CARD_TOKEN`. A mismatch between the EUR request and account currency also returns
`400 Bad Request` with code `UNSUPPORTED_CURRENCY`. These pre-authorization rejections contain only
the code, are not authorization decisions, create no ledger entry, and release the claim while it
is still `PENDING`.

Unexpected technical failures, including fraud deadline/unavailability/corrupt responses, return
`500 Internal Server Error` with code `AUTHORIZATION_PROCESSING_ERROR`; they are not business
decision values. Malformed and technical responses omit field errors and never expose raw gRPC
status, internal exception, or request details. A technical failure after a successful first claim
releases that claim only while it remains `PENDING`. Fraud-engine request conflicts return the
existing HTTP `409 REQUEST_ID_CONFLICT` response and release the new claim.

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

- Authoritative categorical fraud assessment.
- Deterministic synthetic risk score.
- Matched fraud-rule codes, severities, evidence, and score contributions.

See [fraud-assessment.md](fraud-assessment.md) for the fraud-assessment contract. The score is an
informational prioritization value, not a probability or authorization-policy input, and it is not
included in the public HTTP response.

## Generated authorization result

Authorization processing generates:

- Final decision.
- Decision reason.

Case creation is asynchronous and is never exposed through or allowed to change this merchant HTTP
response.

## Separation of responsibilities

Merchant input describes the transaction being attempted. Trusted internal information reflects platform-held facts that the merchant cannot assert or alter. Calculated results are produced from the request and trusted context. Keeping these groups separate preserves trust in the assessment and final decision.

## Synthetic example

Authorization request `d5e75b60-a263-4f76-b5d0-a35f1a09bc67` uses synthetic card token
`tok_A1B2C3D4` at merchant `merchant-123`, with MCC `5732`, for EUR 1,200.00 in `DE`
through the `ECOMMERCE` channel. The merchant simulator also supplies the transaction time. The
platform obtains trusted internal information and generates the fraud and authorization results
separately.

## Demo-only synthetic fixtures

The fraud engine's documented synthetic configuration recognizes `merchant-review` as `REVIEW`
and `merchant-high-risk` as `HIGH_RISK`. The deterministic in-memory adapter mirrors those values
only in focused authorization-service tests; it is not the production runtime adapter.

The Cycle 3 PostgreSQL seed contains these synthetic EUR card accounts:

- `tok_A1B2C3D4` has a posted balance of EUR 1,000.00 and no reserved amount.
- `tok_insufficient01` has a posted balance of EUR 0.00 and no reserved amount.

The available balance is `posted_balance - reserved_amount`. An amount less than or equal to the
available balance produces `PASSED`; a larger amount produces `INSUFFICIENT_FUNDS`. Other valid
card tokens are rejected as `UNKNOWN_CARD_TOKEN` unless a synthetic account is added explicitly.

These identifiers are demo-only fixtures, not production data, configuration, or fraud rules.
