# Authorization Request

## Merchant/acquirer simulator input

The V1 authorization request contains:

- `requestId`: Unique request identifier used later for duplicate detection.
- `cardToken`: Synthetic card token; never a real card number.
- `merchantId`.
- `merchantCategoryCode`: Merchant category code (MCC).
- `amount`.
- `currency`: Explicit ISO currency code.
- `country`.
- `channel`: `POS`, `ECOMMERCE`, or `ATM`.
- `transactionTime`.

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

Authorization request `AUTH-10001` uses synthetic card token `CARD-TKN-204` at merchant `MERCHANT-440`, with MCC `5732`, for EUR 1,200 in `DE` through the `ECOMMERCE` channel. The merchant simulator also supplies the transaction time. The platform obtains trusted internal information and generates the fraud and authorization results separately.
