# 0006 — Indexed metadata and separated material content

Status: Proposed

Date: 2026-10-12

Deciders: maintainers

## Context

Tenant-wide JSON scans load unrelated cases and attachment bodies. String timestamps sort inconsistently when fractional
seconds have different precision. Core-only tests also depended on migration files in the API checkout.

## Decision

Keep extensible record metadata as JSON, and project case ID, order ID, complaint type, status, state, and resource scope
into indexed SQL columns. Related-record and recovery queries use explicit filters, keyset cursors, and bounded limits.
Store immutable attachment bodies in a separate table; metadata reads and audit hashes never repeat the body.
Use database timestamps for record, deadline-order, and audit ordering. Audit pagination binds timestamp and event ID.
Own SQL and portable historical-data migrations in the core classpath, and run real Flyway migration in core tests.

## Consequences

H2 and PostgreSQL share the same query semantics. Historical rows require a one-time projection and attachment migration.
Metadata and content creation remain in one transaction. Query scopes are a small storage interface supplied by callers;
authentication and roles belong to the application boundary. Metrics report global SQL counts and label bounded samples.
A future schema split remains possible without introducing a database-specific JSON query contract.

## Rejected alternatives

Full JSON scans would read unrelated cases and repeat large attachment bodies. Inline attachment content makes every metadata read expensive. String timestamps misorder fractional-second representations. Database-specific JSON expressions would couple migrations and query behavior to one dialect.

## Verification

Run PlatformStoreTest and IndexedPlatformStoreTest with real classpath Flyway migrations. Verify legacy attachment migration, tenant and case/status filtering before limits, scoped pages, CAS/audit rollback, persisted restart, and audit ordering across .001/.120500 timestamps and equal-time event IDs. Confirm generated schema indexes with JDBC metadata.
