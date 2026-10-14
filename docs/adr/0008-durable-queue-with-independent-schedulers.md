# 0008 — Durable queue with independent schedulers

Status: Proposed

Date: 2026-10-12

Deciders: maintainers

## Context

An in-memory queue loses restart state. A shared scheduler makes slow recovery scans delay worker heartbeats and deadline
checks. Depending directly on quality configuration also prevents the queue from being introduced independently.

## Decision

Persist task state, idempotency bindings, immutable input, evidence, configuration, lease, and quota reservations.
Use indexed status queries with limits for recovery, and fence every result against the current worker lease.
Run recovery, heartbeat, and deadline actions on separate schedulers. Keep online execution and offline evaluation pools
separate. Log failure codes and trace IDs without logging customer payloads or SQL exception messages.
Depend on an execution-runtime interface. Demo supplies frozen seed rules; pilot requires a published versioned provider.
Invoke only the trusted workflow entry point with a server-created runtime binding.

## Consequences

Cancellation and late results cannot overwrite a terminal state. Database outages retain recoverable tasks and emit
observable failure counters. Completed worker futures expose unexpected exceptions through the same safe counters and
code-only logs; cancellation remains a normal terminal outcome. Queue capacity and tenant limits remain transactional. The versioned quality provider can
be added later without a queue-to-quality implementation dependency.

## Rejected alternatives

An in-memory-only queue loses idempotency and recovery state on restart. One scheduler couples slow recovery scans to deadline and lease enforcement. A direct dependency on quality configuration makes the queue impossible to introduce independently.

## Verification

Run ExecutionServiceTest for concurrent idempotency, bounded global admission, fair tenant scheduling, lease recovery, timeout, cancellation, partial dependency failure, server-owned author identity, and a blocked recovery scan while heartbeat and deadline schedulers remain active.
