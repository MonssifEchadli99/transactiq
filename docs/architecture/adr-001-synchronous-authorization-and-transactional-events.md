# ADR-001: Synchronous authorization and transactional events

- **Status:** Accepted
- **Date:** 2026-08-06

## Context

A caller needs an immediate, idempotent authorization result, while downstream case processing
must survive broker outages and at-least-once delivery. Sending the authorization request through
Kafka would change the public interaction model; publishing directly after a database commit can
lose an event.

## Decision

Accept authorizations through REST, call fraud assessment synchronously over the versioned gRPC
contract, and atomically persist the authorization, reservation, idempotency state, and serialized
completed-event outbox row in PostgreSQL. An independent relay publishes that exact event to
Kafka. Consumers validate and deduplicate it using stable identities.

## Consequences

- The HTTP response does not wait for Kafka, and broker unavailability does not undo a completed
  authorization.
- The fraud RPC has an explicit deadline and no client retry; fraud-engine separately deduplicates
  request IDs so a caller retry cannot double-count velocity.
- Kafka delivery is at least once, so every consumer must remain idempotent and recovery-aware.
- There is no distributed transaction across services. Completion-to-case visibility is
  asynchronous and operationally monitored.

See the [authorization lifecycle](../business/authorization-lifecycle.md) and
[completed-event contract](../business/authorization-completed-event.md).
