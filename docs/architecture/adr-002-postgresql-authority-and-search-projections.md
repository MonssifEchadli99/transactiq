# ADR-002: PostgreSQL authority and OpenSearch projections

- **Status:** Accepted
- **Date:** 2026-08-06

## Context

Claiming and resolving a fraud case require transactional invariants, optimistic concurrency, and
an immutable audit history. Search and investigation retrieval need query shapes that do not
belong in the command model and must not expose the complete stored snapshot.

## Decision

Case Management owns the authoritative PostgreSQL case and lifecycle state. Each successful
create, claim, or resolution writes a full projection snapshot to a transactional outbox. Kafka
distributes snapshots to independent OpenSearch search and investigation indexes. Public
projection documents use explicit safe-field allowlists and stable aliases.

## Consequences

- Case reads and mutations remain correct when Kafka or OpenSearch is unavailable.
- Search and investigation are eventually consistent and return sanitized unavailable behavior
  rather than falling back to cross-service database reads.
- Projection consumers must enforce version/integrity rules, tolerate redelivery, and support
  controlled alias-based rebuilds.
- OpenSearch is not a source of truth and cannot authorize a lifecycle transition.

See the [case model](../business/fraud-case-management.md) and
[projection recovery runbook](../operations/fraud-case-projection-recovery.md).
