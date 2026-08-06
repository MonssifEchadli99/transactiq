# Architecture decision index

Only decisions that shape several TransactIQ modules are recorded here. Detailed field-level and
business behavior remains in [`docs/business`](../business); the records do not duplicate it.

| ADR | Status | Decision |
|---|---|---|
| [ADR-001](adr-001-synchronous-authorization-and-transactional-events.md) | Accepted | Keep authorization synchronous; publish completed facts with a transactional outbox. |
| [ADR-002](adr-002-postgresql-authority-and-search-projections.md) | Accepted | Keep cases in PostgreSQL and rebuildable query/evidence views in OpenSearch. |
| [ADR-003](adr-003-grounded-read-only-ai-boundary.md) | Accepted | Restrict AI and MCP to validated, cited, read-only investigation assistance. |

The current set is deliberately capped at three high-value decisions. Deployment mechanics are
documented in the [GCP blueprint](../operations/gcp-deployment-blueprint.md), not as another ADR.
