# 0001. Delete unreachable code before adding features

- Status: Accepted
- Date: 2026-08-18
- Deciders: maintainers

## Context

A review of the initial commit found that roughly 16% of main source
(~490 of 3,138 non-blank, non-comment lines) could not be reached from any
request path, and that three of the nine advertised modules contributed
nothing to the default runtime path.

The problem was not the wasted lines. It was that the unreachable code
described capabilities the system did not have, and readers — including
future maintainers and anyone evaluating the repository — could not tell the
difference without tracing call sites by hand:

| Component | What a reader would assume | What was true |
| --- | --- | --- |
| `FallbackHandler` | Model outages degrade gracefully | `LlmGateway` throws; a model outage is an HTTP 500 |
| `VectorSearchService` | Retrieval is embedding-based | Retrieval is `String.contains` with three constant scores |
| `PromptRegistry` | Prompts are versioned and registered | Both workflows concatenate prompt strings inline |
| `ticket_query`, `order_trajectory` | Six tools are callable | Four are; there is no tool-calling loop to reach the rest |
| `CitationService` | Citations are produced here | `OutputFormatter.buildCitations()` is on the response path |
| `AuditService`, `ReplaySimulator` | Auditing and replay exist | Zero call sites |

Two options were available: wire each component up, or remove it.

## Decision

Remove them, in a pull request that makes no behavioural change at all, before
any functional work lands.

The ordering is the point. A "delete-only" diff is cheap to review — a reviewer
verifies each removal has zero call sites and stops there. Once deletions are
mixed with fixes, that cheap check becomes impossible and the diff has to be
read line by line.

Components are removed rather than kept-and-wired when the wiring would have to
be redesigned anyway. `VectorSearchService`'s cosine loop over an in-memory map
is not the implementation a real vector store would use, so keeping it saves no
future work.

## Consequences

**Positive**

- The remaining code is reachable. The module list now describes the system.
- Later pull requests are reviewable as behaviour changes rather than as a
  mixture of deletion and change.
- `ToolGateway.listDefinitions()` no longer advertises tools the API cannot
  invoke.

**Negative**

- Removed scaffolding must be rewritten if the corresponding feature is built.
  This is accepted: none of the removed code was closer than a rewrite away
  from working, and git history preserves it.
- The repository looks smaller. Line count was never the metric.

**Explicitly not removed**

- `TraceService` and `MetricsService` have no writer and therefore always return
  empty, but they back three public endpoints and one test. Deleting them is an
  API-breaking change; wiring them costs ~15 lines. They are fixed in
  [ADR-0002](0002-rule-engine-owns-the-compensation-amount.md)'s pull request
  instead. Delete-first is a default, not a rule that survives contact with a
  cheaper alternative.
- `StructuredOutputParser` is unit tested and is required to make compensation
  output authoritative. Only its unused injection into `LlmGateway` was removed.
- `ModelRouter.registerModel()` is unused today but is the binding point for
  configuration-driven scenario routing in the next pull request.

## Verification

`./mvnw clean verify` must pass with the test suite unchanged. No test
referenced any removed class, so a passing suite is evidence that the removals
were inert.
