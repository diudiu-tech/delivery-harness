# 0006. Three-valued evidence ledger and deterministic snapshots

- Status: Accepted
- Date: 2026-11-20
- Deciders: maintainers

## Context

In `0.2.0`, three defects undermined auditability and evaluation honesty:

1. **Silent zero coercion.** Missing or out-of-order timestamps degraded to
   `0`, turning a missing delivery timestamp into "no anomaly" and a missing
   promised timestamp into inflated overtime.
2. **Non-reproducible snapshots.** `OrderFixtures.build(orderId, now)` derived
   timestamps relative to wall-clock `now()` and used timezone-less
   `LocalDateTime`, so consecutive runs on the same order produced different
   timestamps and could not be hashed or replayed deterministically.
3. **Evaluation label leakage.** `OrderFixtures` placed `scenario_key` into
   `OrderInfo.extraInfo`, which was serialized verbatim into the model prompt.

## Decision

Introduce a three-valued, closed, canonically hashed evidence model:

- **Three-valued `EvidenceValue<T>` on Java 17.** `sealed interface`
  permitting `Known(value, source, observedAt, asOf)`, `Unknown(reasonCode, detail)`,
  and `Invalid(rawValue, violation)` with compile-time exhaustive `fold(...)`.
  `Known` deliberately omits `boolean stale`: staleness is a policy function of
  `(observedAt, asOf)` against `PolicyBundle.stalenessPolicySeconds`, so policy
  changes never invalidate stored snapshot hashes.
- **Closed `EvidenceSnapshot` with canonical SHA-256.** Every fact carries a
  stable `EV-*` identifier, explicit `ZoneId`, and `asOf` timestamp.
  `CanonicalJson` sorts map keys lexicographically, serializes `BigDecimal`
  with `toPlainString()`, and formats timestamps as ISO-8601 with zone offset
  before computing SHA-256.
- **Single `EvidenceSource` SPI (ADR-0004).** `SyntheticEvidenceSource` uses a
  frozen fixture clock (`2026-09-01T12:00:00+08:00`), returns `Optional.empty()`
  for unknown order IDs, carries `SpeakerRole` on conversation metadata rather
  than delegating sender identity to an LLM, and never embeds scenario labels.
- **Physical and bytecode label isolation.** Ground-truth contract expectations
  live exclusively in `harness-core/src/test/resources/eval/labels.json`.
  `ArchitectureGuardTest` inspects compiled JVM constant pools to ensure
  production code never references `labels.json`, `policy` never depends on
  `extraction` or `llm`, and pure kernel packages never depend on Web/HTTP/LLM.

## Consequences

**Positive**

- Collecting the same fixture order twice yields byte-identical canonical JSON
  and SHA-256 `contentHash`.
- Human attestations (`withHumanAttestation`) append immutable `EV-HUMAN-*`
  records and recompute the snapshot hash without mutating prior snapshots.
- Zero external test-framework dependency is needed to enforce package and
  label isolation offline.

**Negative**

- Adding a new evidence fact requires registering an `EvidenceType` enum value
  and a stable `EV-*` identifier rather than putting ad-hoc keys into a map.

## Verification

- `EvidenceValueTest` and `OrderTimelineTest` verify exhaustive `fold(...)`
  handling and propagation of missing, reversed, and malformed-zone inputs.
- `EvidenceSnapshotTest` verifies canonical SHA-256 repeatability, `asOf`
  propagation, unknown-order `Optional.empty()`, zero label leakage across all
  contract fixtures, and immutable `withHumanAttestation(...)`.
- `ArchitectureGuardTest` and `ApiArchitectureGuardTest` verify compiled
  bytecode and controller boundaries on every `./mvnw clean verify`.
