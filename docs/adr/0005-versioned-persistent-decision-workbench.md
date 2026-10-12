# ADR-0005: Versioned persistent decision workbench

- Status: accepted
- Date: 2026-09-30
- Requirements: [Product PRD](../prd/delivery-harness-product-prd.md), FR-01–14

The synchronous demonstration API cannot safely hold an approved business decision: its tools synthesize orders, its traces disappear on restart, and model explanations lack verifiable citations. Internal case handling needs trusted scope, durable facts, independent review and reproducible releases.

We retain the three Maven modules and fixed workflows. `platform` in core supplies JDBC records, atomic audit, version checks, scoped cases, bounded online workers, a separate evaluation executor and immutable evidence/configuration payloads. Flyway owns schema changes. H2 supports a local demonstration and persistence tests; PostgreSQL is the supported deployment target to validate in pilot. Dynamic policy and knowledge payloads live as versioned JSON, while tenant, kind, scope, risk, due date and version are indexed relational projections.

Authority belongs to frozen rule/evidence snapshots. A model may explain a decision but cannot write facts, set authoritative amounts, approve cases or invoke funds endpoints. Unknown facts remain null. Publishing a runtime requires an independently approved passing labelled report for its exact configuration hash. Withdrawal blocks new use and preserves historical frozen content.

`demo` is explicitly synthetic. `pilot` fails startup without identities, persistent storage and a configured allowlisted read-only source. Pilot rejects legacy v1 routes because their unscoped in-memory stores and synthetic tools cannot satisfy the new boundary. Tokens and scopes are supplied by server configuration for this first internal deployment; rotation, TLS termination and identity lifecycle belong to the deployment's trusted gateway. Browser state never supplies roles or identity.

The payment extension is separately disabled. A reservation and its case/audit state commit atomically; PENDING records form the outbox. Once dispatched, uncertain results are queried rather than blindly retried. Enabling a real channel also requires current funds eligibility, provider idempotency, lookup and signed callbacks. This code is a contract adapter, not evidence of financial authorization or a completed live channel certification.

Consequences: a single process remains easy to run and test, with a durable queue that can recover by leases and fencing. JSON storage keeps the versioned domain readable but requires explicit scope projections and bounded queries. Large tenant volumes, multi-node saturation, real policy sign-off, backup objectives and effectiveness experiments must be validated before expanding a pilot. No vector store, autonomous planner or production deployment is implied by these changes.
