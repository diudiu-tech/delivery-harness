# Changelog

All notable changes to this project will be documented in this file. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed

- Avoid quadratic currency-amount matching on long model-generated digit runs.
- Align the Compose Ollama image with the Dockerfile at 0.34.0 and enable
  Dependabot updates for the root Docker Compose manifest.
- Update both READMEs to reflect the current 116-test suite.
- Treat unparseable model compensation amounts as conflicts requiring review.
- Align the root and module POM versions with 0.2.0 for release validation.

### Changed

- Upgrade the optional Ollama runtime from 0.32.4 to 0.34.0.
- Update the security policy to direct reports to GitHub private vulnerability reporting.

## [0.2.0] - 2026-09-01

### Changed

- Bound the in-memory tool invocation log and expose its capacity as
  `harness.observe.max-tool-invocations`.
- Distinguish a completed workflow with an unavailable optional dependency as
  `DEGRADED` instead of reporting it as a clean `SUCCESS`.
- Bound the in-memory evaluation-case store and add a limit to case listing.
- Added configurable bounds for evaluation runs, knowledge documents and chunks.

## [0.1.0] - 2026-08-26

### Added

- Synthetic orders that vary by order ID across six scenarios, including one
  delivered on time so that "no anomaly" is a reachable correct answer.
- Deterministic `OrderTimeline` splitting an order into dispatch wait,
  to-shop, merchant prep, and on-road legs, reported as the baseline the model
  must beat.
- Rule, case, and evaluation seed data loaded at startup, replacing an
  unexecuted SQL migration.
- Trace and metric recording for every workflow execution, with a bounded
  trace store (`harness.observe.max-traces`).
- `LlmClient` interface with `HttpLlmClient` implementation, so both workflows
  can be tested end to end without a running model.
- Architecture Decision Records under `docs/adr/`.
- English and Simplified Chinese documentation.
- MIT license and GitHub community health files.
- Maven Wrapper, CI, CodeQL, and Dependabot configuration.
- Unit and application smoke tests.

### Fixed

- Tool arguments are derived from the order under analysis. ETA coordinates,
  station ID, and the retrieval query were previously hard-coded, so every
  request assembled identical evidence.
- `overtime_minutes` is computed from the order timeline instead of the
  literal `10`, which had made the two upper compensation tiers unreachable
  through the API.
- Workflow steps record their real duration; every step previously reported
  `0`.
- The rule engine owns the compensation amount and the guardrail validates
  that amount rather than the model's.
- `needs_human_review` and `approval_required` are derived from guardrail
  outcome, parse success, model confidence, and baseline agreement instead of
  being hard-coded to `true`.
- A failed order lookup aborts the workflow instead of continuing with an
  empty order and reporting every step as successful.
- Retrieval scores by query-term overlap; scores were previously constants
  and multi-term queries could never match.
- Evaluation scorers report "not measured" instead of `1.0` when a case
  declares no expectation, read rule IDs from decision fields rather than
  from citations, and match tools by registry name rather than display label.
- Compensation policy conditions and actions now come from the seeded rule
  source, including explicit damage evidence and manual-review behavior.
- Compensation suggestions keep the deterministic payout available during an
  LLM outage and expose the failed model step instead of returning a 502.
- Evaluation runs report succeeded/failed cases and per-case trace IDs, while
  feedback, order IDs, and evaluation result lookups have explicit boundaries.

### Removed

- The unexecuted `db/migration` schema and seed SQL, replaced by JSON seed
  resources that are actually loaded.
- Classes with no call sites: `FallbackHandler`, `CitationService`,
  `ReplaySimulator`, `AuditService`.
- Beans injected but never invoked: `VectorSearchService`, `PromptRegistry`,
  and the `LlmGateway` methods that were their only callers.
- Tools registered but unreachable without a tool-calling loop: `ticket_query`
  and `order_trajectory`.

### Changed

- Upgraded the build baseline to Java 17 and Spring Boot 3.5.
- Reduced dependencies to components used by the in-memory MVP.
- Pinned the optional Ollama container and bound it to localhost.
- Bound the API to localhost by default and established the first release build.

- Rejected invalid text-chunk overlap values that could prevent loop progress.
- Reused request trace IDs throughout workflow execution.
- Removed internal exception details from generic API errors.
- Reported advisory guardrail failures accurately in workflow steps.
- Validated evaluation cases, collection elements, and cross-field chunk settings.
- Returned consistent HTTP 404/502 statuses for missing resources and dependency failures.
- Bounded model-provider response bodies and hardened compensation amount parsing.
- Removed Mockito from the test runtime to avoid JDK agent-attachment failures.
