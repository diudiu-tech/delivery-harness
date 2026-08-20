# 0004. Three Maven modules instead of nine

- Status: Accepted
- Date: 2026-08-18
- Deciders: maintainers

## Context

The build had eight Maven modules plus a container definition:
`harness-common`, `harness-gateway`, `harness-agent`, `harness-knowledge`,
`harness-tool`, `harness-llm`, `harness-eval`, `harness-observe`.

A Maven module is a build boundary. It earns its place when something about it
differs from its neighbours — an independent release cycle, a different
consumer, a different runtime, a dependency the rest of the tree must not
inherit, an ownership line worth enforcing.

None of that applied. All eight shipped from one repository, released together
under one version, ran in one process, were maintained by the same people, and
every one of them depended on `harness-common`. Four of them declared exactly
the same dependency set: `harness-common` plus `spring-boot-starter`.

What the split did cost:

- Every cross-cutting change touched several `pom.xml` files. Wiring trace
  recording into the orchestrator — about thirty lines of Java — required
  editing a POM, because `harness-agent` did not depend on `harness-observe`.
- A reader counting modules concluded the system had nine substantial parts.
  Three of them (`observe`, `knowledge`, `eval`) contributed nothing to the
  default runtime path at all. The module list overstated the system, which is
  the same failure ADR-0001 addresses at the class level.
- Reactor ordering and inter-module version management for artifacts nobody
  consumes independently.

The split was expressing a *package* layout through a *build* mechanism. Java
already has a mechanism for that, and it costs nothing.

## Decision

Three modules, in strict dependency order:

| Module | Contains | Depends on |
| --- | --- | --- |
| `harness-common` | DTOs, exceptions, JSON/text helpers, trace context | nothing internal |
| `harness-core` | `agent`, `tool`, `llm`, `knowledge`, `eval`, `observe` | `harness-common` |
| `harness-api` | controllers, validation, exception mapping, filters | `harness-core` |

**Java package names are unchanged.** `com.delivery.harness.agent.workflow`,
`com.delivery.harness.tool.order` and the rest are exactly where they were.
Not one `import` statement changes in this pull request, which is what makes a
move of roughly sixty files reviewable: the diff is renames plus four POMs.

## Consequences

**Positive**

- The module list now describes the system: shared types, the part that
  decides things, the part that speaks HTTP.
- Adding a dependency between two internal concerns is a Java import, not a
  POM edit and a reactor-ordering question.
- Faster builds; no inter-module artifact resolution for artifacts nobody
  consumes on their own.

**Negative**

- Maven no longer enforces the boundary between, say, `agent` and `tool`.
  Nothing stops a controller from importing a toolkit directly. This is a real
  loss and is accepted on the grounds that the boundary was not being enforced
  usefully before either — `harness-agent` already depended on all four of its
  neighbours. If the boundary starts to matter, ArchUnit tests enforce package
  dependency rules inside one module at a fraction of the cost of splitting the
  build again.
- The artifact renames from `harness-gateway-0.1.0-SNAPSHOT.jar` to
  `harness-api.jar`. Nothing external consumes it; both READMEs are updated.

**Why `tool` is inside `core` rather than a separate `harness-adapters`**

The adapter boundary is real and will matter — a gRPC dispatch client and an
HTTP ETA client bring dependencies that the domain must not inherit. Today
every tool is an in-process function returning synthetic data with no
dependency of its own, so the module would enforce nothing. Extract
`harness-adapters` at the moment the first real adapter arrives with its own
dependency, and let that dependency be the reason, rather than pre-creating the
boundary and hoping it earns out.

## When to revisit

Split again when a concrete condition holds, not on principle:

- A module needs an independent release cycle or an external consumer.
- A component brings a heavyweight dependency the rest must not inherit
  (the first real tool adapter is the likely trigger).
- Ownership genuinely diverges and CODEOWNERS at package granularity is no
  longer sufficient.

## Verification

`./mvnw clean verify` must pass with the test suite unchanged. Because no
package or import changed, a passing build is strong evidence the move was
purely structural.
