# 0005. Minimum probabilistic surface and unidirectional LLM routing

- Status: Accepted
- Date: 2026-11-20
- Deciders: maintainers
- Amends / Partially Supersedes: [ADR-0002](0002-rule-engine-owns-the-compensation-amount.md)

## Context

[ADR-0002](0002-rule-engine-owns-the-compensation-amount.md) established that the
rule engine owns the compensation amount rather than the model, while keeping
the model on the path to write reviewer justifications, customer messages, and
escalation flags — and leaving structured abnormal-order attribution in
`AbnormalOrderAnalysisWorkflow` to the model.

Two problems surfaced under review:

1. **Structured attribution was repeating ADR-0002's own anti-pattern.** All
   six `ANA-001`..`ANA-006` rules were arithmetic and threshold comparisons over
   structured numbers (`on_road_minutes` vs `eta`, `capacity_ratio >= 0.85`,
   `overtime == 0`). Sending structured telemetry through a sampled token
   sequence added latency, hallucination risk, and HTTP 502 outages for zero
   reasoning gain.
2. **LLM-authored customer prose and unverified back-flow inverted the trust
   boundary.** Even with slot placeholders, free text outside slots can still
   emit false completion claims ("已为您退款"). Worse, allowing an LLM-extracted
   text claim (such as `CUSTOMER_CHANGED_ADDRESS`) to reduce payout or exempt a
   party creates an attack surface where self-serving rider messages, negated
   sentences ("我没有改地址"), or prompt injections can alter money or fault.

## Decision

Restrict the language model to the **minimum probabilistic surface** where
deterministic code genuinely cannot read unstructured text, and enforce a
strict **unidirectional routing trust boundary**:

1. **Adjudication is invariant to the LLM in both directions.** Payout
   eligibility, amounts, methods, and required evidence are decided solely by
   `SingleSourcePolicyEngine`. Structurally:
   - `SingleSourcePolicyEngine` method signatures never accept `ClaimedFact`.
   - `ArchitectureGuardTest` inspects compiled bytecode to forbid
     `com.delivery.harness.policy` from depending on
     `com.delivery.harness.extraction` or `com.delivery.harness.llm`.
2. **LLM signals only influence routing (unidirectionally toward human review)
   and workbench display.** An unconfirmed `ClaimedFact` may never reduce
   payout, exempt a party, assign fault, or satisfy `PolicyBundle.requiredEvidence`.
   Changing adjudication requires either structured corroboration or human
   `ATTEST` (`EV-HUMAN-*`).
3. **Deterministic verification before any signal is surfaced (`ClaimVerifier`).**
   - Defense-in-depth prompt-injection pre-scan marks `UNTRUSTED_TEXT`, skips
     LLM extraction, and escalates the case.
   - `speakerRole` comes exclusively from `ConversationMessage` metadata;
     self-serving rider statements for `CUSTOMER_CHANGED_ADDRESS` are rejected.
   - Quotes undergo NFKC normalization, minimum-length checking, single-message
     exact offset alignment, and negation-window rejection.
   - Free-text `statement` fields must contain zero digits or currency symbols
     (structural IDs, offsets, and verbatim quotes are validated by schema).
4. **Customer drafts move to deterministic templates.** Replacing the
   LLM-generated customer message from ADR-0002,
   `DeterministicCustomerTemplateRenderer` renders advisory drafts keyed by
   `(AdjudicationStatus, hasExecutionReceipt)`. Completed wording is reachable
   only after `RECORD_EXECUTION_RECEIPT`.
5. **Default zero-LLM operation and 15-second hard budget.** Extraction is
   disabled by default (`harness.llm.extraction.enabled=false`); when enabled,
   the model timeout defaults to `15s` and outages degrade cleanly to
   `RunHealth.DEGRADED` without failing the case.
6. **Honest baseline requirement.** Offline evaluation compares LLM extraction
   against a ~20-line `RegexSignalBaseline` on a held-out paraphrased text
   corpus frozen before the regex was written, with zero-tolerance veto on
   exemption/reduction false positives. If a model fails to beat the regex
   baseline cleanly, the report explicitly advises against enabling LLM
   extraction.

## Consequences

**Positive**

- Zero-LLM startup works out of the box with complete residual diagnosis,
  policy adjudication, and safe customer drafts.
- Prompt injection, negation, self-serving rider text, or LLM failure cannot
  corrupt compensation amounts or suppress human review.
- Every LLM deployment must prove measurable signal-extraction gain over a
  trivial regex baseline.

**Negative**

- Free-form narrative conflict briefs are deferred to `v0.3.1` because their
  reviewer-speed benefit cannot be measured offline in `v0.3.0`.

## Verification

- `ArchitectureGuardTest.policyPackageNeverDependsOnExtractionOrLlm` enforces
  compile-time/bytecode separation on every build.
- `ClaimVerifierAndExtractorTest` verifies negation rejection, self-serving
  rider rejection, known prompt-injection interception (`callCount == 0`),
  free-text digit rejection (while allowing digits inside verbatim quotes and
  evidence IDs), LLM outage degradation, and exact adjudication invariance.
- `OfflineAblationSmokeTest` runs independent hard-gate verification over
  `labels.json` and `text-corpus.json` (dev/holdout) without reusing
  `ClaimVerifier`.
- `LiveLlmAblationReportTest` (`@Tag("live-llm")`, excluded from offline CI)
  runs real models against the same corpus and emits a Markdown report with
  `modelId`, `promptHash`, and exit-clause verdict.
