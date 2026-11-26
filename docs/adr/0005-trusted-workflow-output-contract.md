# 0005. Keep decision authority in trusted workflow inputs and outputs

- Status: Proposed
- Date: 2026-10-12
- Deciders: maintainers

## Context

ADR-0002 already assigns compensation amounts to the rule engine. An amount
alone is insufficient authority: missing facts can look like zero values,
model text can cite an unrelated policy, and customer wording can imply that
an advisory decision has already been paid. Optional model failures also
need a stable result that a reviewer can distinguish from a complete run.

## Decision

Use frozen evidence and policy snapshots for the trusted workflow entry
point. Reject missing bindings before invoking a handler; never fall back
to live rules or synthetic orders in that entry point. Retain the legacy
entry point for the local demonstration.

Preserve unknown and invalid facts explicitly. The rule engine returns an
undetermined decision when required evidence or policy coverage is absent.
Use one compensation-method enum and one approval-threshold property.
Refund calculations must respect the verified refundable balance.

Validate model references against the evidence ID, version, field, value,
unit and rule IDs actually supplied to that execution. Invalid references
cannot become an accepted model conclusion. Generate customer-facing
wording on the server from the authoritative decision and review state;
model explanations remain advisory. This narrows the wording responsibility
originally allowed by ADR-0002 without changing amount ownership.

A model outage preserves a usable deterministic result and reports
`DEGRADED`. It never establishes missing facts or completes a payment.

## Consequences

Consumers must handle nullable unknown values and canonical method IDs.
Positive subminute delays are late even though legacy minute counters are
integers. Model outages return a completed degraded response rather than
HTTP 502, and `customer_message` is a server template. These changes require
explicit breaking-change notes and consumer migration guidance.

Reference checks may reject previously accepted model prose. This is a
visible evidence-validation failure, with the deterministic result still
available for independent review. A template reduces wording flexibility
and requires localization whenever the public decision contract changes.

## Rejected alternatives

- Trust model-selected facts and policy IDs: plausible references are not
  evidence that the execution used those facts or rules.
- Return zero for missing evidence: zero is a decision, not an unknown.
- Keep unconstrained model customer wording: it can claim a payment or
  promise an outcome that this reference application cannot authorize.
- Fail the whole request when only the model is unavailable: it discards
  the independently computed result and conceals the dependency boundary.

## Verification

Timeline, evidence-validator, compensation-tool and workflow tests cover
unknowns, reversed timestamps, second precision, conflicting rules, refund
balances, canonical methods, absent runtime bindings and invalid references.
API regressions cover server-owned wording and model-outage degradation.
No test invokes a payment or a live delivery system.
