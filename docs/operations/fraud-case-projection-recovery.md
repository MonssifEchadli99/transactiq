# Fraud Case Projection Recovery

PostgreSQL is authoritative. Case mutations and immutable full projection snapshots commit in one
transaction. The relay publishes stored bytes to `transactiq.fraud-case.projection.v1`, keyed by
case ID, and marks rows published only after broker acknowledgement. Delivery is at least once.

Hash rule `fraud-case-projection-sha256-v1` is lowercase SHA-256 over the Protobuf snapshot wire
bytes only. Nullable fields retain presence and matched synthetic rules are sorted by rule code.
Duplicate rule codes are canonicalized further by severity, evidence, and score contribution.
Event identity, envelope, Kafka metadata, and publication time are excluded.

For each case, only the lowest non-published aggregate version is eligible for relay claiming.
Later versions remain blocked while a lower row is `PENDING` or `IN_FLIGHT`, including the crash
window after Kafka acknowledgement and before the database records `PUBLISHED`. Multiple relay
instances may publish different cases concurrently but cannot claim different versions of one case.
The current one-partition topic serializes projection publication. PostgreSQL elects its owner with
a database-time lease and generation; a dedicated producer uses stable transactional ID
`<environment>.<topic>.p0`. Kafka producer epochs, rather than the database lease alone, fence late
sends from former owners. Every row is committed in its own Kafka transaction and rebuild/indexer
consumers use `read_committed`, so aborted or fenced records are invisible. Committed-visible
aggregate versions never decrease, preserving deterministic compacted-topic rebuilds.
Local development defaults the environment discriminator to `local`. Every deployed environment
must configure `TRANSACTIQ_ENVIRONMENT` to its own stable value; deployed environments must not all
reuse the literal `local` transactional identity.

The strict physical index is `transactiq-fraud-cases-v1`; aliases are
`transactiq-fraud-cases` and `transactiq-fraud-cases-write`. Document ID equals case ID. A higher
version atomically replaces the full document; stale and same-hash duplicate events are no-ops;
same-version/different-hash events preserve the document and reach the DLT.

## Bootstrap

Bootstrap is disabled by default. Invoke it explicitly with
`--spring.main.web-application-type=none --spring.kafka.listener.auto-startup=false
--fraud-case.projection.bootstrap-enabled=true`. It scans by case ID in bounded batches, maps the
current `NEW`, `IN_REVIEW`, or `RESOLVED` state, and reports inserted/skipped/failed counts. Reruns
are safe and a same-version hash conflict stops the run. The application exits after the command.
It never changes cases or lifecycle audit.

Bootstrap mode suppresses the scheduled projection relay even if Kafka listener auto-startup was
not explicitly disabled. It requires PostgreSQL only; neither Kafka nor OpenSearch is contacted.

## Outages and rebuild

Kafka failure leaves outbox rows pending with bounded backoff; there is no producer-side DLT.
Delivery is at least once, not an atomic PostgreSQL/Kafka transaction. A committed Kafka transaction
whose PostgreSQL `PUBLISHED` mark is lost is republished as an accepted same-version duplicate.
Temporary OpenSearch failures retry and exhausted or invalid events reach the ordinary-retention
projection DLT before source progress continues.

Increasing the projection topic beyond one partition requires an explicit ownership/data-migration
decision. OpenSearch availability cannot block Case Management creation, claim, or resolution.
Increment 5A still provides no public search endpoint; that remains deferred to Increment 5B.

For a manual rebuild, create another versioned index with the approved mapping, replay the compacted
topic from the earliest retained offsets, compare sampled versions with PostgreSQL, then manually
switch aliases. Automated replay, alias migration, deletion, and retention are deferred.

Only synthetic data may be indexed. Raw tokens, token fingerprints, source-event hashes, Kafka
payloads/headers, credentials, and secrets are excluded. Increment 5A has no search endpoint.
