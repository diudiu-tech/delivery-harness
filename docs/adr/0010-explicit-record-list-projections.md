# 0010. Persist explicit summaries for record lists

- Status: Proposed
- Date: 2026-10-15
- Deciders: maintainers

## Context

SQL LIMIT bounds row count but not payload size. Execution records freeze
runtime bundles containing policy and knowledge bodies. Reading a thousand
complete execution records to calculate latency or cost can load large
snapshots that the calculation does not need.

## Decision

Persist an explicit summary projection alongside each record in the same
transaction. List queries select summary_payload and record metadata rather
than payload. The projection includes bounded scalar metadata, cost fields
and authorization scopes; it excludes prompts, knowledge bodies, rules,
samples, attachments and model text. Domain detail endpoints retain their
existing authorization and full immutable snapshots.

Use V8 to add the column and V9 to backfill historical records through the
same projection function. Do not modify applied migrations. Runtime and
evaluation summaries retain every relevant policy, knowledge and evidence
scope. Invalid or oversized scopes remain invalid rather than being silently
truncated to a subset that could change authorization.

## Consequences

List consumers must use explicit counts instead of expecting embedded rules
or documents. Detailed content is fetched separately after authorization.
A projection is a maintained schema: adding a list field requires a deliberate
whitelist update and regression coverage. Database sizing, migration duration
and PostgreSQL execution plans still require deployment-specific validation.

## Rejected alternatives

- Load the full payload then strip fields: the memory cost is already paid.
- Only reduce page size: large legitimate bundles still dominate the request.
- Vendor-specific JSON paths: this reference supports both H2 and PostgreSQL.
- Drop knowledge scopes from summaries: policy scope alone can leak a runtime
  configuration containing knowledge outside the caller's allowed stations.

## Verification

A JDBC regression corrupts the full payload after creating a valid summary;
summary, scoped-page and recent-metrics queries must still succeed. Other
regressions cover historical migration and every runtime/evaluation scope
while asserting private body and prompt text never enters the summary.
