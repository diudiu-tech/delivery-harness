# 0007. Single-source PolicyBundle owns all policy parameters

- Status: Accepted
- Date: 2026-11-20
- Deciders: maintainers

## Context

In `0.2.0`, policy decisions were split across three separate sources that could
drift silently:

1. `harness-core/src/main/resources/seed/rules.json` carried prose descriptions
   ("超时15至30分钟", "部分洒漏，赔付订单金额50%") alongside separate condition
   and action JSON (`min_overtime_minutes: 16`, `partial_cap: 20.00`).
2. `application.yml` and `.env.example` carried `harness.guardrail.max-compensation-amount`
   (`50.0`) and `harness.compensation.approval-threshold` (`10.0`) outside the
   rule set.
3. Attribution rules (`ANA-001` through `ANA-006`) relied on vague prose
   ("2倍以上", "显著超过") without executable thresholds or baselines.

Additionally, zero payouts conflated four distinct business outcomes: an
affirmative decision not to pay (`INELIGIBLE`), missing evidence
(`NEEDS_EVIDENCE`), an uncovered complaint type (`NO_APPLICABLE_POLICY`), and
an unsupported order status (`OUT_OF_SCOPE`). Meanwhile, a calculated full
damage payout of `64.00` exceeded the `50.00` limit yet was still returned as
`suggested_amount`.

## Decision

Consolidate every policy parameter into a single versioned, typed JSON bundle
(`harness-core/src/main/resources/policy/policy-v1.0.0.json`) loaded and
validated fail-fast at startup by `PolicyBundleLoader` and
`PolicyBundleValidator`:

- **No expression language.** Rules use fixed typed `amountType` enums
  (`FIXED`, `PERCENT_CAPPED`, `DAMAGE_LEVEL`, `MANUAL`) interpreted by Java
  `switch` in `SingleSourcePolicyEngine`.
- **Single source for prose and execution.** `CompensationRule.renderDescription()`
  generates human-readable policy explanations directly from the executable
  thresholds, rates, caps, and approval flags.
- **No scattered config properties.** Remove
  `harness.guardrail.max-compensation-amount`,
  `HARNESS_MAX_COMPENSATION_AMOUNT`, and
  `harness.compensation.approval-threshold`; both `CaseApplicationService` and
  `SingleSourcePolicyEngine` read `PolicyBundle.limits`.
- **Startup fail-fast validation.** For every `OVERTIME` rule set, intervals
  must start at `0`, have no overlaps or gaps, and end with an unbounded top
  tier. `routingMatrix` must map every `AdjudicationStatus`.
- **Explicit six-state adjudication.** Separate `ELIGIBLE`, `INELIGIBLE`,
  `NEEDS_EVIDENCE`, `EXCEEDS_CAP_ESCALATE`, `NO_APPLICABLE_POLICY`, and
  `OUT_OF_SCOPE`. Only `ELIGIBLE` and `INELIGIBLE` produce a non-null
  `actionableAmount`; `EXCEEDS_CAP_ESCALATE` keeps `actionableAmount == null`
  and surfaces `calculatedAmount` for supervisor escalation.
- **Single active bundle in v0.3.** No multi-version selector machinery is built
  while only one bundle ships (ADR-0001). An evaluation timestamp outside
  `[effectiveFrom, effectiveTo)` yields `NO_APPLICABLE_POLICY`.
- **Locked attribution thresholds and normal-state baselines (anti-gaming).**
  Defined once here before the residual attribution engine (`PR4`) is written:
  - `minExcessMinutes = 8`: filters minor delivery timing noise.
  - `dominanceMarginMinutes = 5`: requires a clear lead before naming a single
    primary candidate; otherwise reports `MULTI_FACTOR`.
  - `normalDispatchBaselineMinutes = 4`, `toShopBaselineMinutes = 8`,
    `normalMerchantPrepBaselineMinutes = 10`: represent normal-state SLA
    baselines rather than strained real-time station averages.
  - `strainedCapacityRatio = 0.85`: station capacity strain corroboration.
  - `PR4` must not tweak these constants or fixture numbers to force labels;
    any future change requires a standalone PR with before/after fixture diffs.

## Consequences

**Positive**

- Policy text and executable thresholds cannot drift apart.
- Missing evidence, uncovered complaints, non-delivered orders, and over-cap
  payouts are cleanly distinguished and never surfaced as actionable payouts.
- Staleness is evaluated dynamically against `stalenessPolicySeconds` without
  mutating evidence snapshots.

**Negative**

- Operators cannot override the escalation cap via environment variable alone;
  changing policy limits requires editing the versioned `PolicyBundle`. This is
  intentional: policy limits are policy, not deployment tuning.

## Verification

- `PolicyBundleTest` verifies fail-fast rejection of overlapping intervals,
  interval gaps, missing zero-start, bounded top tiers, duplicate rule IDs,
  invalid limits, and incomplete routing matrices, as well as all six
  `AdjudicationStatus` outcomes, effective-window boundaries, dynamic staleness
  checks, and auto-rendered descriptions.
- `CaseApiTest` verifies end-to-end `NEEDS_EVIDENCE` (missing
  `DAMAGE_LEVEL` or timestamps), `EXCEEDS_CAP_ESCALATE` (`64.00 > 50.00`
  returning no actionable amount until supervisor `APPROVE` with
  `exception_reason`), `OUT_OF_SCOPE` (`ORD-INTRANSIT-01`), and
  `NO_APPLICABLE_POLICY` (`MISSING_ITEM`).
