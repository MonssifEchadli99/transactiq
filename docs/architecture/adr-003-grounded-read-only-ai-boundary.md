# ADR-003: Grounded, read-only AI investigation boundary

- **Status:** Accepted
- **Date:** 2026-08-06

## Context

An analyst-facing assistant is useful only when its factual output can be traced to published case
evidence. Retrieved evidence can contain hostile instruction-like text, and generated structure,
citations, or provider diagnostics cannot be trusted. An AI integration must not become another
case-command path.

## Decision

Retrieve only safe, integrity-checked evidence through the investigation application service.
Treat evidence as untrusted data. Require structured generation with stable source identifiers,
then reject malformed output, unknown citations, and uncited factual findings before mapping
identifiers to public citation metadata. Expose the same retrieval and answer services through REST
and exactly two read-only MCP tools. Provide no claim, resolve, approve, block, or assignment port.

Non-demo configuration uses the configured provider and fails closed when credentials or a valid
generation are unavailable. Cycle 8's explicit `demo-offline` profile substitutes deterministic
offline embeddings and answer fixtures; it cannot be selected accidentally by non-demo
configuration and does not weaken validation or logging rules.

## Consequences

- Answers are advisory, can report `INSUFFICIENT_EVIDENCE`, and never decide or mutate a case.
- Provider output is an untrusted proposal until server-side validation succeeds.
- Prompts, questions, evidence, answers, provider bodies, credentials, private markers, and raw
  exceptions are excluded from logs and public DTOs.
- Citation validation improves traceability but does not prove semantic correctness or eliminate
  all model risk; human review remains required.

See the [AI investigation guide](../business/ai-investigation-assistant.md) and
[threat model](../security/threat-model.md).
