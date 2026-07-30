# Fraud Assessment

## Purpose

The fraud engine evaluates a transaction and returns a fraud assessment. This document records the
fraud-assessment contract and the six rules implemented in Cycle 4.

## Assessments

- `CLEAR`
- `REVIEW`
- `HIGH_RISK`

The assessment is the authoritative categorical business result. The numeric score described below
is informational and never replaces or changes the assessment.

## Rule matching and severity

A fraud rule that fires produces one matched rule with a stable rule code, a severity (`REVIEW` or
`HIGH_RISK`), and concise synthetic evidence. The overall assessment equals the highest severity
among matched rules. `CLEAR` means no rule matched; it is not itself a rule severity.

## Result contents

A fraud assessment result contains the overall assessment, an integer risk score from 0 through
100, and every matched rule's code, severity, concise evidence, and score contribution.

## Synthetic risk score

The risk score is a deterministic synthetic prioritization value. It is not a probability, an ML
prediction, or a real-world fraud model. It does not participate in rule matching, assessment, or
authorization decisions, and it is not exposed through the public authorization HTTP response.

Each matched rule contributes once. The engine sums the individual contributions and caps the
total at 100. No matches produces `CLEAR` with score 0. Review-only matches produce `REVIEW` with a
score from 1 through 69. Any high-risk match produces `HIGH_RISK` with a score from 70 through 100.
The categorical assessment remains derived only from the highest matched severity.

The default synthetic contributions are:

| Rule | `REVIEW` | `HIGH_RISK` |
|---|---:|---:|
| `AMOUNT_THRESHOLD` | 15 | 70 |
| `MERCHANT_PROFILE` | 15 | 75 |
| `RISKY_MCC` | 10 | 70 |
| `TRANSACTION_COUNT` | 10 | 70 |
| `ROLLING_AMOUNT` | 15 | 70 |
| `COUNTRY_SWITCH` | unsupported | 80 |

These values are external Spring configuration passed into a framework-independent scoring
policy. Startup rejects missing, unknown, unsupported, duplicate, non-integer, out-of-range, or
categorically unsafe mappings. Changing the contributions does not change rule thresholds or
assessment behavior.

## Synthetic, configurable rules

Fraud rules, windows, thresholds, merchant profiles, and MCC classifications are synthetic
demonstration configuration. They do not represent real-world fraud classifications or production
fraud-detection logic.

The implemented stateless rules are:

- `AMOUNT_THRESHOLD`: thresholds are configured independently per currency. The default synthetic
  EUR configuration produces `REVIEW` at amounts greater than or equal to EUR 1,000.00 and
  `HIGH_RISK` at amounts greater than or equal to EUR 2,500.00. Crossing the high-risk threshold
  produces one `HIGH_RISK` match rather than two matches. An unconfigured currency does not match,
  and the rule performs no currency conversion.
- `MERCHANT_PROFILE`: exact merchant identifiers map to a configured severity. The default
  synthetic mappings are `merchant-review` to `REVIEW` and `merchant-high-risk` to `HIGH_RISK`.
- `RISKY_MCC`: exact MCC values map to a configured severity. The default synthetic mappings are
  `7995` to `REVIEW` and `6051` to `HIGH_RISK`.

The implemented stateful rules use one velocity snapshot that includes the current attempt:

- `TRANSACTION_COUNT`: counts attempts for the same synthetic card-token fingerprint in a rolling
  window. The default synthetic window is 60 seconds. Attempts one through four do not match,
  attempts five through nine produce `REVIEW`, and attempt ten or above produces `HIGH_RISK`.
- `ROLLING_AMOUNT`: sums exact decimal amounts for the same token fingerprint and currency. The
  default synthetic window is five minutes. For configured EUR totals, amounts below EUR 3,000.00
  do not match, totals from EUR 3,000.00 through EUR 4,999.99 produce `REVIEW`, and totals at or
  above EUR 5,000.00 produce `HIGH_RISK`. Different currencies are never combined, unconfigured
  currencies do not match, and no currency conversion occurs.
- `COUNTRY_SWITCH`: compares countries observed for the same token fingerprint in the default
  synthetic ten-minute window. The first country and repeated use of only that country do not
  match. A current attempt in another country while a different country remains in the window
  produces `HIGH_RISK`; this rule has no `REVIEW` severity.

Every configured rule is evaluated once. Every match is returned in stable rule-code order, and
the overall assessment is derived only from the highest matched severity: no matches means
`CLEAR`, review-only matches mean `REVIEW`, and any high-risk match means `HIGH_RISK`.

## Velocity observation and window semantics

The fraud engine records every new valid assessment attempt before evaluating the rules, so the
current attempt is present in its snapshot. Recording uses fraud-engine observation time from its
server clock, not the client-supplied `transactionTime`. An event older than or exactly at a
window's cutoff is expired before the current attempt is recorded. Each assessment obtains one
immutable snapshot, and all six rules evaluate that same snapshot exactly once.

Velocity is grouped by a stable SHA-256 fingerprint of the synthetic card token. The full token is
never written to Redis keys, logs, evidence, or errors. Rolling amounts are additionally isolated
by currency and are aggregated with exact decimal arithmetic; Redis timestamp scores are not used
for money.

## Request deduplication

Redis stores a canonical SHA-256 fingerprint of all fraud-relevant request fields under the
request identifier. A new identifier records one attempt and its original velocity snapshot. An
identical retry returns that stored snapshot and does not add another attempt. Reusing the same
identifier with different fraud-relevant data is a conflict. The default synthetic deduplication
retention is 24 hours and must be longer than every configured velocity window.

Velocity history and deduplication entries have configurable expirations. Redis is temporary
operational state for fraud velocity; it is not the authoritative authorization ledger. Attempts
remain counted even if authorization-service later declines them, including for insufficient
funds.

## Technical failures are not fraud declines

A malformed request returns gRPC `INVALID_ARGUMENT`, unavailable or unexpectedly failing Redis
storage returns `UNAVAILABLE`, and conflicting reuse of a request identifier returns
`FAILED_PRECONDITION`. No technical failure is represented as `CLEAR`, `REVIEW`, or `HIGH_RISK`.
The gRPC boundary enforces the same field syntax documented for the
[authorization request](authorization-request.md), including protobuf timestamp validity, before
the use case records any velocity state.

## Synchronous authorization boundary

The fraud engine exposes all six rules through the versioned `FraudAssessmentService` and its
synchronous `Assess` RPC. For each valid, newly claimed authorization execution,
authorization-service sends the request identifier, synthetic card token, merchant and MCC, exact
decimal amount string, currency, country, channel, and exact transaction instant. It maps the
response into a framework-independent model; protobuf and gRPC types remain inside the outbound
adapter and configuration.

The gRPC response explicitly includes the total score, including zero for `CLEAR`, and every match
explicitly includes its contribution. Authorization-service validates field presence, score and
contribution ranges, the capped sum, and agreement among score, matches, severities, and assessment.
Unspecified/unrecognized values, blank codes or evidence, absent scoring fields, or inconsistent
results are technical contract failures, never business fraud results.

The local client defaults to `localhost:9090` with a two-second deadline. Host and port can be
overridden with `TRANSACTIQ_FRAUD_GRPC_HOST` and `TRANSACTIQ_FRAUD_GRPC_PORT`; deadline and transport
mode are also configurable. Plaintext is enabled only for documented local development. There is
no discovery, load balancing, or client retry in this increment, and the managed channel is shut
down gracefully with the application.

See [authorization-decision-policy.md](authorization-decision-policy.md) and
[authorization-lifecycle.md](authorization-lifecycle.md) for authorization mapping, ordering, and
claim-release behavior.

## Synthetic example

Synthetic request `f2b1c9d0-6e3a-4c1b-9b7a-2b6a1e9c7d44` is for EUR 2,600.00 at
`merchant-review` with MCC `7995` in `DE` via `ECOMMERCE`. The amount rule produces an
`AMOUNT_THRESHOLD`/`HIGH_RISK` match, while the configured merchant and MCC produce
`MERCHANT_PROFILE`/`REVIEW` and `RISKY_MCC`/`REVIEW` matches. Because `HIGH_RISK` is the highest
matched severity, the overall assessment is `HIGH_RISK`. The result reports all three matched
rules with concise evidence. Their contributions are 70, 15, and 10, so the synthetic risk score is
95. The assessment would remain `HIGH_RISK` regardless of those numeric contribution values.
