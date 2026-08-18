## Summary

Describe the problem and the chosen solution. If this pull request removes
something, say what made it safe to remove.

## Verification

- [ ] `tools/check-format.sh` and `./mvnw clean verify` both pass
- [ ] Tests cover the changed behavior, and would fail without the change
- [ ] English and Chinese READMEs are updated when user-facing behavior changes
- [ ] An ADR is added under `docs/adr/` if a reviewer would ask "why not just…?"
- [ ] No secrets or real personal/order data are included
- [ ] Mock, in-memory, and planned behavior is labeled accurately

## What could this break?

Name the most likely failure this change introduces and how it would surface.
Write `Nothing — behavior is unchanged` only if the diff genuinely cannot alter
runtime behavior, and say why.

## Security and data impact

Describe changes to inputs, model prompts, tools, persistence, permissions, or
sensitive data handling. Write `None` if not applicable.

Answer explicitly if the change touches any of these:

- the compensation amount, the approval threshold, or the guardrail
- anything that reaches the model prompt (knowledge ingestion is a
  prompt-injection trust boundary)
- what is written to logs or traces
