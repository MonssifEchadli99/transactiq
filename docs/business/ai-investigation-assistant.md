# AI Fraud-Investigation Assistant — Retrieval Foundation (Cycle 6, Increment 6A)

## Purpose and scope

Increment 6A builds the safe retrieval foundation for a future AI fraud-investigation
assistant. It consumes the existing Fraud Case projection, produces safe synthetic evidence
chunks, embeds them with OpenAI through Spring AI, indexes them in a dedicated OpenSearch
vector index, and exposes a read-only hybrid-retrieval REST endpoint.

**6A generates no AI answers.** There is no chat, no prompt orchestration, no Structured
Outputs, and no conversation history. It returns retrieved evidence excerpts only.

The future assistant is strictly advisory. It cannot and never will:

- approve or decline an authorization;
- change a fraud score or assessment;
- claim or resolve a fraud case;
- perform any other business mutation.

PostgreSQL remains the sole business source of truth. This module's OpenSearch index is an
eventually consistent read/retrieval projection, exactly like `case-search-service`'s index. It
is rebuildable only when a complete replayable set of Fraud Case projections (or an upstream
bootstrap that republishes one current full snapshot per case) is available.

## Module

`investigation-assistant-service` is a new Java 21 / Spring Boot 4.1.0 module. It has no
PostgreSQL/JDBC dependency and no client capable of a business mutation. It consumes the
existing Fraud Case projection topic under its own dedicated Kafka consumer group, so its
read/replay position never interferes with `case-search-service`.

### Hexagonal boundary

Application logic is separated from adapters. In particular, an application-owned
`EmbeddingPort` (Strategy pattern) abstracts embedding generation:

- production strategy — `OpenAiEmbeddingAdapter`, backed by Spring AI's OpenAI embedding
  client;
- test strategy — a deterministic, offline `DeterministicEmbeddingAdapter` used only in
  tests.

There is exactly one production AI provider. There is intentionally no provider enum,
factory, runtime provider selection, or fallback — 6A does not anticipate a second provider.

## Safe evidence model

At most two evidence documents are produced per fraud case:

| Source type | Created when | Stable source identifier |
|---|---|---|
| `CASE_EVIDENCE` | Always | `case:{caseId}:evidence` |
| `RESOLUTION` | Only for a valid `RESOLVED` snapshot with complete resolution metadata | `case:{caseId}:resolution` |

Each source identifier is also the deterministic OpenSearch document ID, so a newer
projection snapshot for the same case replaces the same logical evidence document instead of
creating duplicates.

For an unresolved snapshot, the resolution slot is represented internally by a non-retrievable
completeness marker. It has no case metadata, evidence text, or vector, so focal and hybrid queries
cannot return it; its only purpose is to prevent a raced older snapshot from resurrecting obsolete
resolution evidence and to make partial-write repair detectable.

### Safe-field allowlist

`SafeEvidenceMapper` explicitly reads only these approved fields from the full projection
snapshot and writes them into canonical, deterministically ordered evidence text:

- synthetic case ID, case status;
- merchant ID and merchant category code;
- amount and currency;
- country and channel;
- authorization-occurred-at timestamp;
- authorization decision;
- fraud assessment and risk score;
- for each matched rule: rule code, severity, score contribution, and its already-synthetic
  evidence text — sorted by rule code, then severity, then evidence, then contribution, so
  ordering never depends on Kafka delivery order;
- for the resolution chunk only: resolution outcome, synthetic resolution rationale, and the
  resolved-at timestamp.

Nothing else on the projection is read. The mapper never serializes the projection or any
rule-evidence map directly — every field is named explicitly, so an unknown or newly added
projection field is silently omitted rather than leaking into evidence text.

### Explicitly excluded from embedding text, provider payloads, REST responses, and logs

- authorization request IDs;
- card tokens, card-token fingerprints, or account identifiers;
- assignee or resolution-actor identities;
- projection event IDs;
- snapshot/payload hashes;
- Kafka topic, partition, offset, or headers;
- infrastructure endpoints or credentials;
- raw OpenAI provider requests/responses;
- embedding vectors;
- OpenSearch relevance scores.

The projection's `aggregateVersion` and validated snapshot integrity discriminator are stored
**privately** on each evidence document purely for version-aware, same-snapshot idempotency. The
case-evidence document also carries private `publicationComplete` and `resolutionExpected` flags.
They are never embedded, logged, returned by the REST API, or included in future model context.

All identifiers, merchant names, and rationale text used anywhere in this module — including
every automated test — are synthetic portfolio data.

## Projection ingestion

`InvestigationProjectionConsumer` consumes `transactiq.fraud-case.projection.v1` under the
`transactiq-investigation-assistant-v1` consumer group (separate from
`case-search-service`'s `transactiq-case-search-v1`). For each record:

1. Parse the Protobuf payload; a malformed payload is a permanent failure.
2. `ProjectionValidator` structurally validates the event (UUID identifiers, key/case
   identity, aggregate-version and snapshot-hash agreement, event-type/status agreement,
   required-field presence) — independently from `case-search-service`'s own validator, so
   each consumer group can route its own malformed input to its own dead-letter topic.
3. `SafeEvidenceMapper` produces one or two evidence drafts.
4. Before requesting an embedding, `ProjectionIngestionService` checks whether the indexed case
   state is complete for the snapshot's expected chunk set. A lower incoming version is stale; an
   equal version with the same integrity discriminator is a duplicate; both are successful no-ops.
   An equal version with a different discriminator is a permanent integrity failure. A partial
   equal-version write is repaired instead of being mistaken for a complete duplicate.
5. Otherwise, the draft's canonical text is sent to the `EmbeddingPort`. The returned vector's
   dimension is validated against the configured expected dimension; a mismatch is a
   permanent failure.
6. The case-evidence document is first written with `publicationComplete=false`, then the
   deterministic resolution slot is written as either `ACTIVE` or the private `ABSENT` marker.
   The case evidence is marked complete only after both documents have the same version and
   integrity discriminator and the expected chunk state is present. Every write uses a
   version-aware compare-and-swap, and the final store operation rechecks completeness.
7. Focal and related-case retrieval independently revalidate that publication barrier. An
   incomplete focal snapshot returns the sanitized unavailable response; an incomplete related
   case is excluded. Mixed generations such as newer evidence plus an older resolution are never
   exposed to REST or future model context.
8. Kafka only acknowledges the record after every draft for that snapshot has been
   successfully indexed (or skipped as a complete no-op).

### Retry and dead-letter behavior

Permanent failures (invalid projection contract, malformed Protobuf, same-version/different-snapshot
integrity conflict, or wrong embedding dimension) are never retried and go straight to the dedicated
`transactiq.investigation-assistant.projection.v1.dlt` topic. Transient OpenAI or OpenSearch
failures are retried with a bounded fixed backoff before falling back to the same DLT — they
are never silently committed and dropped. This intentionally reuses `case-search-service`'s
simpler retry/DLT shape rather than `case-management-service`'s heavier
exponential-backoff/recovery-header design, since this module has no PostgreSQL transaction to
coordinate with.

A repeated embedding request after a partial failure (e.g. the embedding succeeded but the
OpenSearch write then failed, and Kafka redelivers) is an accepted, documented limitation. The
retry may pay for a second, discarded embedding call. Until replay completes the matching
document set and publication marker, the partial case is retrieval-ineligible. If bounded retries
end in DLT recovery, that barrier remains closed, so DLT exhaustion cannot expose split evidence;
a later successful replay safely repairs and publishes the snapshot.

## OpenAI embeddings

- Default model: `text-embedding-3-small` (environment-configurable via
  `TRANSACTIQ_EMBEDDING_MODEL`).
- Expected dimensions: 1,536.
- Similarity: cosine.

**Changing the embedding model or its dimensionality requires a new physical index version
and a full evidence rebuild.** The index mapping's vector field is a fixed-dimension,
fixed-space-type `knn_vector`; nothing in this module attempts to migrate vectors produced by
a different model or dimension count in place.

The common OpenAI API key is read from the `OPENAI_API_KEY` environment variable
(`spring.ai.openai.api-key` binds to it). Spring AI gives an explicitly configured
`spring.ai.openai.embedding.api-key` precedence; TransactIQ validates that effective value and
rejects an empty or whitespace override before readiness. No key or credential is ever committed.
Automated tests never call OpenAI — they wire a deterministic `EmbeddingPort` test double instead.
Evidence texts, vectors, API keys, and raw OpenAI request/response bodies are never logged.
The OpenAI Java SDK's independent diagnostics are also disabled operationally: `OPENAI_LOG` must
be absent or equal `off` case-insensitively, otherwise startup fails before any provider request.

## Configuration

The table below covers every module-owned setting plus the connection and model settings that
6A explicitly overrides. Spring Boot relaxed binding also permits the uppercase underscore form
of each property (for example, `INVESTIGATION_ASSISTANT_CONSUMER_GROUP_ID`).

| Property or explicit environment variable | Default | Purpose |
|---|---|---|
| `spring.application.name` | `investigation-assistant-service` | Stable Spring application identity. |
| `spring.ai.model.chat` | `none` | Keeps answer/chat generation disabled in 6A. |
| `OPENAI_API_KEY` | Empty | Common OpenAI-key fallback. It is required unless a nonblank `spring.ai.openai.embedding.api-key` is configured; a blank effective key fails startup before Kafka or REST becomes ready. |
| `spring.ai.openai.embedding.api-key` | Not configured | Optional Spring AI embedding-specific override. When present it takes precedence over the common key and must be nonblank after trimming. |
| `OPENAI_LOG` | Absent or `off` | OpenAI Java SDK diagnostic logging guard. Any other value fails startup so SDK request/response bodies cannot bypass application logging controls. |
| `TRANSACTIQ_EMBEDDING_MODEL` | `text-embedding-3-small` | The sole production embedding model. A model change requires compatibility review and a new index version. |
| `spring.ai.openai.embedding.options.dimensions` | `1536` | Requested OpenAI vector dimensions; must stay aligned with the mapping and expected-dimension guard. |
| `TRANSACTIQ_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka bootstrap servers. |
| `spring.kafka.consumer.enable-auto-commit` | `false` | Prevents offsets from advancing independently of record processing. |
| `spring.kafka.consumer.auto-offset-reset` | `earliest` | A brand-new group starts at the earliest retained projection. |
| `spring.kafka.consumer.isolation-level` | `read_committed` | Hides aborted/fenced projection transactions. |
| `spring.kafka.consumer.key-deserializer` / `value-deserializer` | Kafka byte-array deserializer | Preserves the Protobuf key and value bytes for explicit validation. |
| `spring.kafka.listener.ack-mode` | `record` | Commits progress only after each successfully handled or recovered record. |
| `spring.kafka.producer.acks` | `all` | Requires broker acknowledgment when publishing a recovered DLT record. |
| `spring.kafka.producer.key-serializer` / `value-serializer` | Kafka byte-array serializer | Preserves original record bytes on the DLT path. |
| `investigation-assistant.consumer.topic` | `transactiq.fraud-case.projection.v1` | Existing compacted full-snapshot projection topic. |
| `investigation-assistant.consumer.group-id` | `transactiq-investigation-assistant-v1` | Dedicated 6A consumer group. Use a fresh, temporary group for a manual replay. |
| `investigation-assistant.consumer.dlt-topic` | `transactiq.investigation-assistant.projection.v1.dlt` | Dedicated investigation projection DLT. |
| `investigation-assistant.consumer.topic-partitions` | `1` | Partition count used when declaring the source and DLT topics. |
| `investigation-assistant.consumer.retry-interval` | `1s` | Fixed delay between transient retry attempts. |
| `investigation-assistant.consumer.retry-attempts` | `5` | Bounded transient delivery attempts before DLT recovery. |
| `TRANSACTIQ_OPENSEARCH_URL` | `http://localhost:9200` | OpenSearch endpoint. |
| `investigation-assistant.opensearch.request-timeout` | `2s` | OpenSearch request timeout. |
| `TRANSACTIQ_INVESTIGATION_EVIDENCE_INDEX` | `transactiq-fraud-investigation-evidence-v1` | Versioned physical evidence index. |
| `TRANSACTIQ_INVESTIGATION_EVIDENCE_READ_ALIAS` | `transactiq-fraud-investigation-evidence` | Read alias used by focal and hybrid retrieval. |
| `TRANSACTIQ_INVESTIGATION_EVIDENCE_WRITE_ALIAS` | `transactiq-fraud-investigation-evidence-write` | Designated write alias used by projection ingestion. |
| `investigation-assistant.opensearch.hybrid-pipeline` | `transactiq-fraud-investigation-evidence-hybrid-v1` | OpenSearch 3.2 search pipeline containing the RRF processor. |
| `investigation-assistant.embedding.expected-dimensions` | `1536` | Application-side dimension guard applied before indexing. |
| `investigation-assistant.retrieval.candidate-pool-size` | `50` | BM25/k-NN candidate count sent to OpenSearch before related-case grouping. |
| `investigation-assistant.retrieval.focal-text-max-length` | `2000` | Maximum focal evidence characters appended to the analyst question for retrieval. |
| `investigation-assistant.retrieval.excerpt-max-length` | `500` | Maximum characters returned per evidence excerpt. |
| HTTP/message-converter/Spring AI logger levels | `INFO` | Prevents DEBUG request-body rendering from exposing evidence or questions. |
| Apache HTTP header/wire logger levels | `OFF` | Prevents credentials and provider/OpenSearch wire bodies from entering logs. |

The Kafka consumer remains `read_committed`, starts at the earliest retained offset for a new
group, disables auto-commit, and acknowledges one record only after successful indexing or DLT
recovery. Those safety settings are intentional and should not be weakened through deployment
overrides. With `ack-mode=RECORD`, the error handler explicitly keeps `ackAfterHandle=true`: a
failed DLT send future is rethrown and leaves the source offset uncommitted, while a successfully
published recovery returns normally and lets the container commit that record.

## OpenSearch index and hybrid retrieval

A dedicated, strict index (conceptually `transactiq-fraud-investigation-evidence-v1`) is
provisioned idempotently at startup, with read (`transactiq-fraud-investigation-evidence`) and
write (`transactiq-fraud-investigation-evidence-write`) aliases. Startup verifies `index.knn`,
strict mapping, vector dimension, and cosine space compatibility. It may add a missing expected
alias when no conflicting target exists, but never deletes, recreates, repoints, or adopts an
unexpected alias target. Incompatible indexes and aliases fail startup.

The mapping declares: a `text` field for BM25, a 1,536-dimension `knn_vector` field (HNSW,
Lucene engine, cosine similarity) for k-NN, and safe `keyword` metadata (`sourceId`,
`sourceType`, `caseId`) for focal lookup and grouping. Projection version, the non-indexed snapshot
integrity discriminator, chunk state, expected-resolution flag, and publication-complete marker
are private consistency fields.

A dedicated OpenSearch 3.2 search pipeline is also provisioned idempotently, running the
`score-ranker-processor` with the `rrf` (Reciprocal Rank Fusion) technique to fuse BM25 and
k-NN rankings — TransactIQ controls indexing, BM25, k-NN, and RRF explicitly; Spring AI's
generic `VectorStore`/RAG advisors are intentionally not used.

### Retrieval flow

`POST /api/v1/fraud-cases/{caseId}/investigation/retrieval`:

1. Load both deterministic focal document IDs and validate one complete published generation. If
   neither exists yet, return `404`; if a partial or inconsistent generation exists, return the
   sanitized `503` response.
2. Build a bounded retrieval text from the analyst's question plus the focal case's safe,
   high-signal evidence text (truncated deterministically).
3. Embed that retrieval text.
4. Execute a genuine OpenSearch hybrid query — BM25 `match` + k-NN, fused by the RRF search
   pipeline — with a query-level filter excluding the focal case ID.
5. Batch-validate each candidate case's private publication generation and exclude incomplete,
   inconsistent, or stale-generation hits before they can enter application/model context.
6. Group the remaining ranked hits by related case ID (an application-side step performed *after*
   OpenSearch's own RRF fusion — no pretend score is computed locally), so a related case
   contributing two chunks occupies exactly one of the requested result slots instead of
   consuming two.
7. Return at most the requested number of related cases, each with its excerpt(s).
8. Where OpenSearch scores/ranks tie, an explicit deterministic tie-breaker (source ID
   ascending) keeps ordering stable.

## REST contract

```
POST /api/v1/fraud-cases/{caseId}/investigation/retrieval
Content-Type: application/json

{
  "question": "Why is this transaction suspicious?",
  "maxRelatedCases": 5
}
```

- `question`: required, trimmed server-side, 1–1,000 characters after trimming.
- `maxRelatedCases`: optional, default 5, range 1–10.
- `caseId`: validated as a UUID path variable, consistent with `case-management-service`'s
  existing fraud-case REST convention.

Example response:

```json
{
  "caseId": "b7c1d0a2-...",
  "focalSources": [
    {
      "sourceId": "case:b7c1d0a2-...:evidence",
      "sourceType": "CASE_EVIDENCE",
      "caseId": "b7c1d0a2-...",
      "excerpt": "Fraud case b7c1d0a2-... status IN_REVIEW. Merchant merchant-review category 7995. ..."
    }
  ],
  "relatedCases": [
    {
      "caseId": "a4e2f918-...",
      "sources": [
        {
          "sourceId": "case:a4e2f918-...:evidence",
          "sourceType": "CASE_EVIDENCE",
          "caseId": "a4e2f918-...",
          "excerpt": "Fraud case a4e2f918-... status RESOLVED. Merchant merchant-review category 7995. ..."
        }
      ]
    }
  ]
}
```

No vector, provider detail, projection version, raw OpenSearch score, or Kafka metadata is
ever present in the response.

### Status codes

- `400` — invalid case ID, question, or `maxRelatedCases`.
- `404` — the focal case has no evidence indexed yet (the index is eventually consistent; a
  very recently created case may not have caught up).
- `503` — the embedding provider or OpenSearch is unavailable, or focal evidence is present but
  its private publication generation is incomplete/inconsistent.

Error bodies follow `case-management-service`'s existing sealed `{code}` /
`{code, fieldErrors}` shape. Raw internal or provider exceptions are never exposed.

## Focused verification

Java 21 and a running Docker engine are prerequisites. All automated embeddings are deterministic
test doubles; these commands do not need an OpenAI key and must not make a live provider request.

```powershell
# Safe mapping, lifecycle, integrity, and pre-embedding behavior
.\gradlew.bat :investigation-assistant-service:test --tests "*SafeEvidenceMapperTest" --tests "*ProjectionValidatorTest" --tests "*ProjectionIngestionServiceTest"

# Real OpenSearch 3.2 provisioning, controlled BM25/k-NN/RRF, and concurrent integrity behavior
.\gradlew.bat :investigation-assistant-service:test --tests "*OpenSearchEvidenceStoreIntegrationTest" --tests "*OpenSearchProjectionIntegrityIntegrationTest"

# DEBUG/TRACE request safety, SDK diagnostics, and effective-key startup checks with zero outbound requests
.\gradlew.bat :investigation-assistant-service:test --tests "*InvestigationRequestLogSafetyTest" --tests "*InvalidInvestigationRequestLogSafetyTest" --tests "*OpenAiApiKeyStartupTest"

# Kafka retry, genuine DLT-send failure, split-snapshot gating/repair, and the production REST path
.\gradlew.bat :investigation-assistant-service:test --tests "*InvestigationProjectionKafkaIntegrationTest" --tests "*InvestigationRetrievalApiIntegrationTest"

# Complete module verification
.\gradlew.bat :investigation-assistant-service:test --rerun-tasks
```

## Eventual consistency

This index can lag PostgreSQL and `case-search-service`'s own index by however long Kafka
delivery and embedding/indexing take. A case created moments ago may briefly return `404`
from the retrieval endpoint. This mirrors `case-search-service`'s own documented eventual
consistency.

## Index rebuild

Increment 6A has **no dedicated rebuild or replay tool**. Its consumer can rebuild into a new
index only from records that Kafka actually retains, or from projections republished by an
operator-approved upstream bootstrap. Before starting, verify the source topic, its compaction and
retention configuration, its earliest retained offsets, and the availability of a current full
snapshot for every case that must be searchable. A compacted topic need not retain every
intermediate lifecycle version, but it must retain the complete projection set needed to reconstruct
current state. If retention or data loss removed a case's last full snapshot, a complete rebuild is
impossible from Kafka alone. In other words, Kafka cannot be the rebuild source when the complete
retained projection history needed for current state is unavailable; first use the authoritative
upstream projection bootstrap/republication process documented in the
[Fraud Case projection recovery runbook](../operations/fraud-case-projection-recovery.md).

For the repository's local Kafka container, inspect the topic policy and retained offset range
before replaying:

```powershell
docker compose exec kafka /opt/kafka/bin/kafka-configs.sh --bootstrap-server localhost:19092 --entity-type topics --entity-name transactiq.fraud-case.projection.v1 --describe
docker compose exec kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:19092 --topic transactiq.fraud-case.projection.v1 --time earliest
docker compose exec kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:19092 --topic transactiq.fraud-case.projection.v1 --time latest
```

Never write vectors from a changed model or dimension into the existing physical index. A model,
dimension, vector-space, or incompatible mapping change requires a new versioned physical index and
a full evidence rebuild.

For a manual local rebuild and cutover:

1. Keep the existing service and aliases serving the v1 index. Choose a new physical name such as
   `transactiq-fraud-investigation-evidence-v2`, temporary v2 read/write aliases, and a brand-new
   replay consumer group. Confirm `OPENAI_API_KEY` is provided outside source control and that the
   intended model and checked-in mapping agree on dimensions.
2. Start a temporary rebuild instance with the new physical index and temporary aliases. A fresh
   group uses `auto-offset-reset=earliest` and consumes from the earliest retained records:

   ```powershell
   $env:TRANSACTIQ_INVESTIGATION_EVIDENCE_INDEX = "transactiq-fraud-investigation-evidence-v2"
   $env:TRANSACTIQ_INVESTIGATION_EVIDENCE_READ_ALIAS = "transactiq-fraud-investigation-evidence-v2-rebuild"
   $env:TRANSACTIQ_INVESTIGATION_EVIDENCE_WRITE_ALIAS = "transactiq-fraud-investigation-evidence-v2-rebuild-write"
   $env:INVESTIGATION_ASSISTANT_CONSUMER_GROUP_ID = "transactiq-investigation-assistant-rebuild-v2"
   .\gradlew.bat :investigation-assistant-service:bootRun
   ```

3. Monitor that replay group's lag until it reaches zero. Compare document counts and sampled case
   versions with the authoritative Fraud Case state; inspect the DLT and stop on any integrity error.
4. Stop the normal v1 writer, leave the rebuild consumer running until it is caught up again, then
   stop the rebuild instance. This creates a bounded cutover point while the normal consumer group's
   committed offset remains available.
5. Atomically move the stable read and write aliases with an explicit operator action. Startup never
   performs this repoint automatically and never deletes either physical index:

   ```powershell
   $openSearchUrl = "http://localhost:9200"
   $oldIndex = "transactiq-fraud-investigation-evidence-v1"
   $newIndex = "transactiq-fraud-investigation-evidence-v2"
   $readAlias = "transactiq-fraud-investigation-evidence"
   $writeAlias = "transactiq-fraud-investigation-evidence-write"
   $cutover = @{
     actions = @(
       @{ remove = @{ index = $oldIndex; alias = $readAlias } }
       @{ remove = @{ index = $oldIndex; alias = $writeAlias } }
       @{ add = @{ index = $newIndex; alias = $readAlias } }
       @{ add = @{ index = $newIndex; alias = $writeAlias; is_write_index = $true } }
     )
   } | ConvertTo-Json -Depth 5
   Invoke-RestMethod -Method Post -Uri "$openSearchUrl/_aliases" -ContentType "application/json" -Body $cutover
   ```

6. Restart the normal service with its stable consumer group, the v2 physical index, and the stable
   aliases. It resumes after its last committed offset and writes any cutover-gap records into v2.
7. Recheck consumer lag, focal retrieval, related-case grouping, and several synthetic cases before
   retaining v1 as the rollback target. Index deletion and retention remain explicit operator work.

## Out of scope for 6A

Generated investigation answers, chat/prompt orchestration, Structured Outputs, a RAG
evaluation dataset, MCP, UI, authentication, a gateway, GCP/Terraform deployment, pgvector or
any other vector store, multi-provider support, conversation history, and streaming. Cycle 5's
REST contracts, `case-search-service`, its OpenSearch index, fraud scoring, authorization
decisions, and fraud-case creation/claiming/resolution are all unchanged.
