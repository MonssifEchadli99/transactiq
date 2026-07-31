# Cycle 5 Fraud-Case Creation Acceptance

Automated tests are the authoritative acceptance mechanism. They use PostgreSQL and
Kafka Testcontainers where infrastructure is required; the complete local stack does not need to
be started manually.

For an optional local observation, start `case-postgres` and Kafka, then run
`case-management-service`. It consumes `transactiq.authorization.completed.v1` with group
`transactiq-case-management-v1`.

Inspect only the Case Management database:

```sql
SELECT case_id, source_event_id, request_id, status, assignee_id,
       authorization_decision, decline_reason, fraud_assessment, risk_score
FROM fraud_case.fraud_cases
ORDER BY created_at, case_id;

SELECT case_id, match_order, rule_code, severity, evidence, score_contribution
FROM fraud_case.fraud_case_rule_matches
ORDER BY case_id, match_order;
```

The service must never query or mutate the authorization schema. Invalid events and identity
conflicts are published to `transactiq.authorization.completed.v1.dlt` on the same partition.
Inspect DLT headers and confirm the original bytes are unchanged; exception messages, causes, and
stack traces must not be present. A successful DLT send unblocks the next record. An unavailable or
mispartitioned DLT must leave the source offset uncommitted and retry recovery with a positive delay.
