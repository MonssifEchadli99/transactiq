# Case Management Kafka Recovery

## Contract

The Case Management group `transactiq-case-management-v1` consumes
`transactiq.authorization.completed.v1`. Permanent invalid-event and identity-conflict failures go
directly to `transactiq.authorization.completed.v1.dlt`. Unexpected failures receive five total
processing attempts with 1, 2, 4, and 8 second delays. Concrete temporary database/resource
failures retry indefinitely, doubling from one second and capped at 30 seconds.

The DLT must have at least as many partitions as the source topic. Publication uses the original
partition and exact key/value bytes. Recovery publication waits for broker acknowledgement. Only
after success may the source offset commit. If publication fails, recovery retries with a positive
delay without restarting the exhausted processing-attempt budget.

## Recovery metadata

Standard Spring Kafka source-coordinate headers are accompanied by exactly one of each custom
header: `transactiq-recovery-id`, `transactiq-recovery-category`,
`transactiq-recovery-exception-class`, `transactiq-recovery-at`,
`transactiq-recovery-attempt`, `transactiq-recovery-consumer-group`, and
`transactiq-recovery-payload-sha256`. The recovery ID is `sourceTopic:partition:offset`; categories
are `INVALID_EVENT`, `CONTRACT_CONFLICT`, or `UNEXPECTED_PROCESSING_FAILURE`.

Treat DLT records as potentially duplicated. Operators or future replay tooling must deduplicate by
the recovery ID and must not log payloads. Exception messages, causes, and stack traces are excluded
because they can contain sensitive input or database details.

## Operational checks

- Alert on repeated `kafka_dlt_publication_failed` and `kafka_recovery_retry` events.
- Verify DLT partition count before changing the source topic partition count.
- Do not manually advance a blocked source offset until the failed bytes and recovery consequence
  have been reviewed.
- DLT replay is not part of Increment 2; preserve records for a later controlled replay workflow.
