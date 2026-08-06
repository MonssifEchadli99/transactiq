# AI Fraud-Investigation Assistant — Retrieval, Grounded Answers, and MCP (Cycle 6)

## Purpose and scope

Increment 6A provides the safe retrieval foundation: it consumes the existing Fraud Case
projection, produces safe synthetic evidence chunks, embeds them with OpenAI through Spring AI,
indexes them in a dedicated OpenSearch vector index, and exposes read-only hybrid retrieval.
Increment 6B adds one structured, grounded answer endpoint over exactly that retrieved context.
Increment 6C exposes those same application capabilities as exactly two read-only tools on a real
Model Context Protocol server. It does not create a second retrieval or generation path.

The assistant is strictly advisory and read-only. It cannot:

- approve or decline an authorization;
- change a fraud score or assessment;
- claim or resolve a fraud case;
- perform any other business mutation.

It has no persistence, conversation history, streaming, write-capable tools, agent loop, or
autonomous decision authority. Evidence text is untrusted data inside a delimited prompt section;
text that resembles an instruction never changes the system rules or grants an action capability.

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

Application logic is separated from adapters. An application-owned `EmbeddingPort` (Strategy
pattern) abstracts embedding generation:

- production strategy — `OpenAiEmbeddingAdapter`, backed by Spring AI's OpenAI embedding
  client;
- test strategy — a deterministic, offline `DeterministicEmbeddingAdapter` used only in
  tests.

An equally small `ChatGenerationPort` separates answer orchestration and validation from the
Spring AI/OpenAI production adapter. Tests replace both ports with deterministic local fakes.
There is exactly one production AI provider. There is intentionally no provider enum, factory,
runtime provider selection, or fallback.

The MCP adapter is another inbound adapter. Its tool methods delegate in-process to
`InvestigationRetrievalService` and `InvestigationAnswerService`, so MCP clients receive the same
retrieval, publication-integrity, grounding, citation, and provider safeguards as REST clients.
The adapter has no write-side port and does not duplicate either application workflow.

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

## OpenAI embeddings and chat generation

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

Increment 6B uses `gpt-4.1-mini` by default for one non-streaming chat request. The model is
environment-configurable, the request has an application timeout, temperature is `0.0`, and the
adapter requests a strict JSON-schema response. An optional nonblank
`spring.ai.openai.chat.api-key` (for example `SPRING_AI_OPENAI_CHAT_API_KEY`) takes precedence for
chat; otherwise chat uses the common `OPENAI_API_KEY`. The same startup validation and
`OPENAI_LOG` guard apply to the effective chat key. There is no live provider call in automated
tests.

Analyst questions, retrieved evidence, prompts, generated summaries/findings/checks, provider
request or response bodies, credentials, and raw exceptions are never logged. Application, HTTP,
and MCP DTO `toString()` implementations redact content so accidental structured logging cannot
render those values. The Spring AI `OpenAiChatModel` logger is forced to `OFF` because its
zero-choice warning path can render the prompt; Spring AI MCP and MCP Java SDK logging are also
forced to `OFF` so protocol DEBUG logging cannot render tool arguments or results. The independent
OpenAI SDK diagnostic guard remains `OPENAI_LOG=off` or absent.

## Configuration

The table below covers every module-owned setting plus the connection and model settings that
6A–6C explicitly override. Spring Boot relaxed binding also permits the uppercase underscore form
of each property (for example, `INVESTIGATION_ASSISTANT_CONSUMER_GROUP_ID`).

| Property or explicit environment variable | Default | Purpose |
|---|---|---|
| `spring.application.name` | `investigation-assistant-service` | Stable Spring application identity. |
| `spring.ai.model.chat` | `openai` | Enables the sole production chat adapter. |
| `spring.ai.model.embedding` | `openai` | Enables the existing 6A OpenAI embedding adapter. |
| `spring.ai.model.image`, `spring.ai.model.moderation`, `spring.ai.model.audio.speech`, `spring.ai.model.audio.transcription` | `none` | Explicitly disables unused OpenAI capabilities so the service exposes only chat and embedding model surfaces. |
| `OPENAI_API_KEY` | Empty | Common OpenAI-key fallback. Every provider operation must have a nonblank effective key before Kafka, REST, or MCP becomes ready. |
| `spring.ai.openai.embedding.api-key` | Not configured | Optional Spring AI embedding-specific override. When present it takes precedence over the common key and must be nonblank after trimming. |
| `spring.ai.openai.chat.api-key` / `SPRING_AI_OPENAI_CHAT_API_KEY` | Not configured | Optional chat-specific override. When present it takes precedence over the common key and must be nonblank after trimming. |
| `OPENAI_LOG` | Absent or `off` | OpenAI Java SDK diagnostic logging guard. Any other value fails startup so SDK request/response bodies cannot bypass application logging controls. |
| `TRANSACTIQ_EMBEDDING_MODEL` | `text-embedding-3-small` | The sole production embedding model. A model change requires compatibility review and a new index version. |
| `spring.ai.openai.embedding.options.dimensions` | `1536` | Requested OpenAI vector dimensions; must stay aligned with the mapping and expected-dimension guard. |
| `TRANSACTIQ_CHAT_MODEL` | `gpt-4.1-mini` | The sole production chat model used for structured answer generation. |
| `TRANSACTIQ_CHAT_TIMEOUT` / `spring.ai.openai.chat.timeout` | `10s` | Maximum time allowed by the OpenAI chat HTTP client for one generation request; the same environment value also binds the module validation property. |
| `logging.level.org.springframework.ai.openai.OpenAiChatModel` | `OFF` | Prevents Spring AI warning paths from rendering analyst questions, evidence, or prompts. |
| `spring.ai.mcp.server.enabled` | `true` | Enables the Cycle 6C MCP server. |
| `spring.ai.mcp.server.name` / `version` | `transactiq-investigation-assistant` / `6C` | Stable MCP server identity advertised during protocol initialization. |
| `spring.ai.mcp.server.type` / `protocol` | `SYNC` / `STREAMABLE` | Uses Spring AI's synchronous Streamable HTTP MCP server on the existing Spring MVC application. |
| `spring.ai.mcp.server.streamable-http.mcp-endpoint` | `/mcp` | Single MCP protocol endpoint; it is not a renamed REST controller. |
| MCP capabilities | Tools enabled; resources, prompts, and completions disabled | Keeps the server surface limited to the two documented read-only investigation tools. |
| `logging.level.org.springframework.ai.mcp`, `logging.level.io.modelcontextprotocol` | `OFF` | Prevents protocol logging from exposing tool questions or evidence. |
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

### Grounded answer flow

`POST /api/v1/fraud-cases/{caseId}/investigation/answer` adds generation without opening a
second evidence path:

1. Run the 6A retrieval service for the focal case and at most five related cases. Only complete,
   publication-gated sources can proceed.
2. Flatten those bounded public excerpts into model context with their stable source identifiers.
   The prompt marks that block as untrusted reference data, prohibits following instructions found
   inside it, and restates the advisory/read-only boundary.
3. Request one deterministic structured answer from the configured OpenAI chat model.
4. Reject null or malformed output. `GROUNDED` requires at least one factual finding, and every
   finding requires at least one citation ID. `INSUFFICIENT_EVIDENCE` requires no factual findings.
5. Reject every citation ID that is absent from the exact retrieved-source allowlist. Resolve valid
   IDs server-side to public source ID, source type, case ID, and excerpt metadata.
6. Return only the validated summary, findings, recommended checks, grounding status, and resolved
   citations. No provider response object, prompt, vector, integrity field, private publication
   marker, credential, or assignee/resolver identity reaches the API.

The model may recommend checks an analyst could perform, but the service has no write-side case
port and cannot claim, resolve, block, approve, or otherwise mutate a case.

## REST contracts

### Retrieval contract

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

### Grounded answer contract

```http
POST /api/v1/fraud-cases/11111111-1111-4111-8111-111111111111/investigation/answer
Content-Type: application/json

{
  "question": "Which evidence should I review first?"
}
```

`question` is required, trimmed server-side, and must contain 1–1,000 characters. The case ID is
the UUID path variable; clients cannot provide evidence, citations, model settings, or case actions.

Example grounded response:

```json
{
  "caseId": "11111111-1111-4111-8111-111111111111",
  "summary": "The available evidence highlights a high-severity velocity signal.",
  "findings": [
    {
      "text": "The authorization matched the VELOCITY_SPIKE rule with HIGH severity.",
      "citations": [
        {
          "sourceId": "case:11111111-1111-4111-8111-111111111111:evidence",
          "sourceType": "CASE_EVIDENCE",
          "caseId": "11111111-1111-4111-8111-111111111111",
          "excerpt": "Fraud case 11111111-1111-4111-8111-111111111111 status IN_REVIEW. ..."
        }
      ]
    }
  ],
  "recommendedChecks": [
    "Compare the recent synthetic authorization sequence with the velocity rule evidence."
  ],
  "groundingStatus": "GROUNDED"
}
```

When retrieval does not support a factual finding, the service instead returns a concise summary,
an empty `findings` list, safe recommended checks, and
`"groundingStatus": "INSUFFICIENT_EVIDENCE"`. It does not guess a fact or manufacture a source
identifier.

### Status codes

- `400` — invalid case ID, question, or `maxRelatedCases`.
- `404` — the focal case has no evidence indexed yet (the index is eventually consistent; a
  very recently created case may not have caught up).
- `503` — the embedding/chat provider or OpenSearch is unavailable, focal evidence is present but
  its private publication generation is incomplete/inconsistent, or chat output is malformed or
  fails grounding/citation validation. Retrieval failures use `INVESTIGATION_RETRIEVAL_UNAVAILABLE`;
  provider/answer-validation failures use `INVESTIGATION_ANSWER_UNAVAILABLE`.

Error bodies follow `case-management-service`'s existing sealed `{code}` /
`{code, fieldErrors}` shape. Raw internal or provider exceptions are never exposed.

## MCP contract

Cycle 6C runs a synchronous Spring AI MCP server over Streamable HTTP on the existing Spring MVC
process. The protocol endpoint is `http://localhost:8080/mcp`. Clients use normal MCP
initialization, `tools/list`, and `tools/call` messages; `/mcp` is not an ordinary JSON REST
endpoint. The server advertises tools only—MCP resources, prompts, and completions are disabled.

Tool discovery returns exactly these two tools:

| Tool | Required inputs | Structured output |
|---|---|---|
| `retrieve_fraud_case_evidence` | `caseId` (UUID string), `question` (nonblank string, at most 1,000 characters) | `caseId`, `focalSources`, and `relatedCases`; each public source contains only `sourceId`, `sourceType`, `caseId`, and `excerpt`. |
| `answer_fraud_investigation_question` | `caseId` (UUID string), `question` (nonblank string, at most 1,000 characters) | `caseId`, `summary`, `findings`, `recommendedChecks`, and `groundingStatus`; every finding carries its resolved public `citations`. |

The retrieval tool delegates directly to the 6A service with the existing five-related-case bound.
The answer tool delegates directly to the 6B service, including its `GROUNDED` versus
`INSUFFICIENT_EVIDENCE` rules and citation allowlist. Both tools advertise `readOnlyHint=true`,
`destructiveHint=false`, `idempotentHint=true`, and `openWorldHint=false`. Those hints describe the
real boundary: neither tool has a path to claim, resolve, approve, block, assign, or otherwise
mutate a case.

A Spring AI 2.0 MCP client can connect with this Streamable HTTP configuration (other clients map
the same base URL and endpoint into their own configuration schema):

```yaml
spring:
  ai:
    mcp:
      client:
        streamable-http:
          connections:
            transactiq-investigation:
              url: "http://localhost:8080"
              endpoint: "/mcp"
```

For example, after initialization and tool discovery, the client can issue this MCP tool call:

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "retrieve_fraud_case_evidence",
    "arguments": {
      "caseId": "11111111-1111-4111-8111-111111111111",
      "question": "Which evidence should I review first?"
    }
  }
}
```

A successful retrieval has `isError=false`, the fixed text status
`INVESTIGATION_EVIDENCE_RETRIEVED`, and safe evidence in `structuredContent`. A successful answer
uses `INVESTIGATION_ANSWER_READY` and the answer shape above. The MCP adapter never returns a
prompt, vector, private integrity/publication marker, credential, analyst identity, provider body,
or raw exception.

Tool failures have `isError=true`; both the text content and `structuredContent.code` contain one
stable sanitized code:

| Code | Meaning |
|---|---|
| `INVALID_INVESTIGATION_REQUEST` | The case ID or question is invalid. |
| `FOCAL_EVIDENCE_NOT_FOUND` | No published focal evidence is available yet. |
| `INVESTIGATION_RETRIEVAL_UNAVAILABLE` | Retrieval, embedding, OpenSearch, or evidence publication completeness is unavailable. |
| `INVESTIGATION_ANSWER_UNAVAILABLE` | The provider is unavailable or generation is malformed or fails grounding validation. |

Cycle 6C intentionally adds no authentication or authorization. The local endpoint must not be
exposed to an untrusted network; adding an authenticated deployment boundary remains later work.

## Compact offline evaluation

`src/test/resources/evaluation/grounded-answer-evaluation.json` is a deliberately small synthetic
catalog, exercised by `GroundedAnswerEvaluationTest` through the real 6A retrieval orchestration
and 6B answer validator. Its deterministic fake chat adapter returns only the catalogued draft or a
safe simulated provider failure; it cannot instantiate Spring AI or contact OpenAI.

The nine scenarios cover:

- one clearly supported finding and multiple independently cited findings;
- an explicit `INSUFFICIENT_EVIDENCE` answer;
- rejection of an unknown citation and of an uncited factual finding;
- imperative prompt-injection text retained only as untrusted evidence data;
- a valid finding grounded in related-case evidence;
- a sanitized provider failure; and
- rejection of malformed structured output.

This is a compact regression/evaluation asset for a portfolio increment, not a statistical model
quality benchmark. It proves orchestration and deterministic policy enforcement; it does not score
provider accuracy or compare models.

Cycle 6C adds a compact protocol-level suite that starts the actual Streamable HTTP MCP server and
drives real MCP initialize/session, `tools/list`, and `tools/call` JSON-RPC exchanges through a
minimal HTTP protocol client. It verifies the exact two-tool surface, successful retrieval,
grounded and insufficient-evidence answers, sanitized invalid and unavailable failures, DEBUG-log
redaction, and the absence of a mutation path. Deterministic embedding and generation fakes keep
every automated invocation offline; no test sends an OpenAI request.

## Focused verification

Java 21 and a running Docker engine are prerequisites. All automated embeddings and chat
generations are deterministic test doubles; these commands do not need an OpenAI key and must not
make a live provider request.

```powershell
# Safe mapping, lifecycle, integrity, and pre-embedding behavior
.\gradlew.bat :investigation-assistant-service:test --tests "*SafeEvidenceMapperTest" --tests "*ProjectionValidatorTest" --tests "*ProjectionIngestionServiceTest"

# Real OpenSearch 3.2 provisioning, controlled BM25/k-NN/RRF, and concurrent integrity behavior
.\gradlew.bat :investigation-assistant-service:test --tests "*OpenSearchEvidenceStoreIntegrationTest" --tests "*OpenSearchProjectionIntegrityIntegrationTest"

# DEBUG/TRACE request safety, SDK diagnostics, and effective-key startup checks with zero outbound requests
.\gradlew.bat :investigation-assistant-service:test --tests "*InvestigationRequestLogSafetyTest" --tests "*InvalidInvestigationRequestLogSafetyTest" --tests "*OpenAiApiKeyStartupTest"

# Kafka retry, genuine DLT-send failure, split-snapshot gating/repair, and the production REST path
.\gradlew.bat :investigation-assistant-service:test --tests "*InvestigationProjectionKafkaIntegrationTest" --tests "*InvestigationRetrievalApiIntegrationTest"

# Grounded-answer orchestration, citation enforcement, REST safety, and the nine-scenario offline evaluation
.\gradlew.bat :investigation-assistant-service:test --tests "*InvestigationAnswer*Test" --tests "*GroundedAnswerEvaluationTest"

# MCP tool policy and real Streamable HTTP discovery/invocation with deterministic fakes
.\gradlew.bat :investigation-assistant-service:test --tests "*FraudInvestigationMcpProtocolTest"

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

## Current limitations and out of scope for 6C

- Citation allowlisting proves that a returned source was retrieved and that every factual finding
  names at least one such source. It does not independently prove semantic entailment between the
  source excerpt and natural-language finding; that remains model-quality evaluation work.
- Related context is limited to the five cases selected by the existing 6A hybrid retrieval path.
  Retrieval is eventually consistent and can omit a newly indexed case until ingestion completes.
- Generation is one non-streaming, stateless request. There is no conversation persistence,
  history, cache, UI, authentication/authorization, or multi-provider fallback.
- Prompt-injection resistance is bounded to treating evidence as delimited untrusted data,
  restrictive instructions, structured output, citation validation, and the absence of mutation
  ports or write-capable tools. The model has no autonomous case-decision authority.
- The synthetic offline catalog is intentionally compact; no live-provider quality or latency
  evaluation runs in CI.
- The MCP server is synchronous Streamable HTTP with exactly two tools. It has no MCP resources,
  prompts, completions, progressive answer streaming, downstream tool federation, conversation
  state, or agent loop.
- Cycle 6C has no authentication or authorization and is intended for controlled local portfolio
  use. A production network boundary, per-client policy, quotas, and audit controls are not modeled.

Also out of scope are write tools, case mutations, autonomous fraud decisions, a gateway,
GCP/Terraform deployment, pgvector or another vector store, unrelated Cycle 5 changes, and
modifications to fraud scoring, authorization decisions, or case creation/claiming/resolution
contracts.
