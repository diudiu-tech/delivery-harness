# 0002. The rule engine owns the compensation amount, not the model

- Status: Accepted
- Date: 2026-08-18
- Deciders: maintainers

## Context

The compensation workflow originally ran in this order:

1. `compensation_rule` matched a policy tier and computed `suggested_amount`.
2. That amount was serialised into the prompt.
3. The model was asked to output a `suggested_amount` of its own.
4. A regular expression extracted the model's number and compared it to a cap.
5. The response carried the model's number.

Step 1 already produced the answer. Steps 2 through 5 took a deterministic,
auditable number, passed it through a sampled token sequence, and then spent a
guardrail checking that the sequence had not damaged it.

The cost of that round trip is not only latency and tokens. It is auditability.
A payout traceable to "COMP-001, 50% of order value capped at 20" can be
defended to a regulator, an auditor, or a merchant. A payout traceable to
"the model said 17.75" cannot, and reproducing it requires the same model at
the same temperature with the same context.

The guardrail did not fix this. A cap catches an amount that is too large. It
does not catch an amount that is plausible and wrong — 5 where policy says 20 —
which is the far more likely failure and the one that costs money quietly.

The observation that started this: the tool hard-coded `17.75`, which is 50%
of `35.50`, which was the single order amount the mock `order_query` returned
for every request. The percentage rule was never actually computed.

## Decision

The rule engine decides the amount. The model never proposes one.

The model is asked only for what a lookup table cannot supply:

- a justification a reviewer can read, citing specific evidence fields
- risk flags and evidence gaps
- whether the case is unusual enough to escalate
- customer-facing wording

The guardrail validates the **rule engine's** amount, because that is the
number the response carries. It separately checks the model's language for
promise-like phrases, and flags the case when the model emits a
`suggested_amount` despite being told not to — a model disregarding that
instruction is a signal about the whole output, not just that field.

## Consequences

**Positive**

- Every payout traces to a policy line. `amount_decided_by: "rule_engine"` is
  in the response.
- The amount is reproducible without the model. Same order, same policy, same
  number, forever.
- The model's remaining job is one it is actually good at, and one where being
  wrong is cheap: a weak justification wastes a reviewer's minute, whereas a
  wrong number moves money.
- A model outage degrades the response to "correct amount, no explanation"
  rather than failing the decision.

**Negative**

- The system cannot handle a case the rule set does not cover. That is
  intended: an uncovered case routes to a human rather than to a guess.
- Two prompts and two output schemas now exist where one did before.

**Rejected alternative: delete the model from this path entirely.**
Defensible — the deterministic part carries the decision, and the workflow
would be simpler. Rejected because the justification and escalation signal are
real work that a lookup table cannot do, and because a reviewer reading
"COMP-002, ¥5" with no reasoning is slower than one reading why the tier
applies. If evaluation later shows the justification adds nothing to reviewer
throughput, deleting the call is the right follow-up, and this ADR should be
superseded rather than quietly ignored.

## Verification

`CompensationSuggestionApiTest` asserts the response carries the rule engine's
amount even when the model returns a contradicting one, that the severe tier is
capped, and that a model-proposed amount fails the guardrail while leaving the
payout untouched.
