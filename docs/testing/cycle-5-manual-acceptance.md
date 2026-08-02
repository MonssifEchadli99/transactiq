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
       version, created_at, updated_at, resolution_outcome, resolution_rationale,
       resolved_at, resolved_by,
       authorization_decision, decline_reason, fraud_assessment, risk_score
FROM fraud_case.fraud_cases
ORDER BY created_at, case_id;

SELECT case_id, match_order, rule_code, severity, evidence, score_contribution
FROM fraud_case.fraud_case_rule_matches
ORDER BY case_id, match_order;

SELECT lifecycle_event_id, fraud_case_id, event_type, previous_status, resulting_status,
       previous_assignee_id, resulting_assignee_id, actor_id, case_version, occurred_at,
       resolution_outcome, resolution_rationale
FROM fraud_case.fraud_case_lifecycle_events
ORDER BY fraud_case_id, case_version;
```

The analyst HTTP boundary provides queue, detail, claim, resolution, and history under
`/api/v1/fraud-cases`. Claim uses `{"expectedVersion":0}`. Resolution uses the claimed predecessor
version, one approved outcome, and a mandatory normalized rationale. Both commands use
`X-Analyst-Id`; this is unauthenticated development identity only. History requires no identity,
returns persisted events ordered by case version, and never invents a `CREATED` event. Queue cursors
provide stable traversal only while the matching result set remains unchanged; read-committed
requests do not form one cross-request snapshot.

An exact resolution retry succeeds only for the same normalized actor, outcome, rationale, and
predecessor version. It must not change version or timestamps or add an event. Conflicting commands
must preserve the winner. Resolution must not change authorization outcome, balance, fraud
assessment, risk score, rule evidence, Kafka identity, or source hash. Use only synthetic,
non-sensitive rationale text.

The service must never query or mutate the authorization schema. Invalid events and identity
conflicts are published to `transactiq.authorization.completed.v1.dlt` on the same partition.
Inspect DLT headers and confirm the original bytes are unchanged; exception messages, causes, and
stack traces must not be present. A successful DLT send unblocks the next record. An unavailable or
mispartitioned DLT must leave the source offset uncommitted and retry recovery with a positive delay.

## Increment 5A projection observation

PostgreSQL, Kafka, and OpenSearch Testcontainers tests are authoritative. The compacted main topic
is `transactiq.fraud-case.projection.v1`; its ordinary-retention DLT appends `.dlt`. PostgreSQL
details remain authoritative while OpenSearch catches up. No search endpoint exists in 5A.
