# Authorization-Completed Event V1

## Purpose and topic

Every technically completed authorization creates one immutable
`AuthorizationCompletedEvent` in the `event-contract` module. The Kafka topic is
`transactiq.authorization.completed.v1`. The package and topic carry the `v1` version; existing
field numbers and meanings must not be changed after release.

Validation failures, pre-authorization rejections, technical failures, in-progress duplicates,
and conflicting duplicates do not create this event. An identical completed retry returns the
stored result and does not create or update an event.

## Fields

| Field | Meaning |
| --- | --- |
| `eventId` | Stable UUID for consumer deduplication. |
| `occurredAt` | Server time captured from the injected clock during completion. |
| `requestId` | Authorization idempotency identifier. |
| `cardTokenFingerprint` | Lowercase SHA-256 of the UTF-8 synthetic card token; also the Kafka key. |
| `merchantId`, `merchantCategoryCode` | Synthetic merchant identifiers from the request. |
| `amount`, `currency` | Canonical exact decimal string and ISO currency code. |
| `country`, `channel`, `transactionTime` | Original request context and timestamp. |
| `nonFraudResult` | `PASSED` or `INSUFFICIENT_FUNDS`. |
| `decision`, `declineReason` | Final authorization decision and optional decline reason. |
| `fraudAssessment`, `riskScore` | Fraud result and explicitly present synthetic score, including zero. |
| `matchedRules` | Alphabetically ordered code, severity, evidence, and score contribution tuples. |
| `caseRequired` | `false` for `CLEAR`; `true` for `REVIEW` or `HIGH_RISK`. |

All enums reserve an `UNSPECIFIED` zero value. Protobuf timestamps retain the source instant's
nanosecond precision in the serialized payload. The score and every example or identifier in this
project are synthetic; the score is informational and is not a probability.

`caseRequired` is independent of the authorization decision and primary decline reason. For
example, a synthetic `REVIEW` transaction with insufficient funds is `DECLINED` with
`INSUFFICIENT_FUNDS`, while its event has `caseRequired=true`. This increment emits that signal but
does not create a case or implement a Kafka consumer.

## Transaction and publication boundary

The event is serialized once and inserted into PostgreSQL in the same transaction as the ledger,
fraud score and matches, request completion, and any balance reservation. If serialization or the
outbox insert fails, all completion writes roll back and the application releases the pending
request claim. The immutable protobuf bytes are the publisher's source; publication never rebuilds
the event from current rules or configuration.

The scheduled relay claims due rows with `FOR UPDATE SKIP LOCKED` and a recoverable lease, then ends
the claim transaction before sending to Kafka. A broker acknowledgement is required before the row
is marked `PUBLISHED`. A failed send returns the row to `PENDING`, increments its attempt count,
records only `KAFKA_PUBLISH_FAILED`, and schedules a positive exponentially increasing backoff
bounded by the configured maximum. An expired lease is eligible for another relay instance.

Publication is asynchronous and at-least-once. A process crash after Kafka acknowledges a message
but before PostgreSQL records publication can produce a duplicate. Producer idempotence reduces
some producer retries but does not provide end-to-end exactly-once delivery. Every future consumer
must therefore persistently deduplicate by `eventId`. A DLQ and consumer retry policy belong to a
future cycle.

## Token handling

The raw card token is never stored in the outbox and is never placed in the Kafka value, key,
headers, logs, or publication errors. The partition key is lowercase hexadecimal SHA-256 over the
UTF-8 token, which preserves per-card partition ordering without publishing the token itself.
