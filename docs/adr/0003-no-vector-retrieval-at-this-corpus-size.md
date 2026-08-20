# 0003. No vector retrieval at this corpus size

- Status: Accepted
- Date: 2026-08-18
- Deciders: maintainers

## Context

The repository shipped a `VectorSearchService` with a cosine-similarity loop
over an in-memory map, a comment reading "Production: Replace with Milvus
client", and a roadmap entry for embedding generation and vector retrieval.
Nothing called it. Retrieval was `String.contains` with three constant scores.

The gap between the advertised design and the running code invites an obvious
next step: finish the job, generate embeddings, stand up a vector store. That
step is wrong at this size, and the reason is worth writing down so it does not
get taken by default.

The corpus is a policy set. This repository seeds eleven rules and eight
resolved cases. A real operator's compensation and attribution policy is not
orders of magnitude larger — it is a document a person has to be able to read,
argue about and sign off, which caps it at a few hundred entries.

At that size:

- The entire rule set fits in a prompt. Retrieval is an optimisation, not an
  enabler.
- Exhaustive scoring over a few hundred short strings costs microseconds. The
  data structure a vector index exists to avoid — a linear scan — is the
  correct data structure here.
- Embeddings introduce a second model dependency, a cold-start step, an index
  to keep in sync with the source of truth, and a class of failure where a
  policy edit and its vector disagree.
- Policy lookup is closer to exact match than to semantic similarity.
  "COMP-001" and "超时30分钟以上" are terms an operator actually uses, and
  lexical matching handles them without a nearest-neighbour search that can
  return a confidently wrong neighbour.

## Decision

Retrieval is lexical: tokenise the query, score each item by the fraction of
query terms it contains, weight slightly by source type to break ties.

`VectorSearchService` is deleted rather than left as a placeholder
(see [ADR-0001](0001-delete-unreachable-code-before-adding-features.md)).

## Consequences

**Positive**

- Scores now carry information. Previously every rule scored 0.8 and sorting by
  score only reproduced source-type order.
- Multi-term queries work. The previous implementation tested whether the
  entire query string appeared inside an item, which fails for every query with
  more than one term — including every query the workflows generate.
- No second model dependency, no index to keep in sync, no cold start.
- The retrieval path is testable without a model, and is tested.

**Negative**

- No synonym or paraphrase matching. A query for "出餐慢" will not match a rule
  that only says "备餐时间过长". The mitigation is a `tags` field on each rule,
  which is where an operator can enumerate the phrasings that matter — a
  cheaper and more auditable mechanism than an embedding at this scale.
- CJK text is tokenised on whitespace and punctuation, not by word
  segmentation, so recall depends on the query and the rule sharing a phrase.
  Acceptable while queries are machine-generated from a fixed vocabulary; it
  needs revisiting if free-text operator queries become a use case.

## When to revisit

Reopen this when any of the following is true, and bring the number:

- The corpus exceeds roughly 1,000 entries.
- Free-text operator search becomes a real use case, with measured queries that
  lexical matching misses.
- A measured retrieval-recall deficit is shown to cause attribution errors —
  not assumed to.

Until one of those holds, an embedding pipeline here has an unbounded idiot
index: the cost is real and the numerator it buys is zero.
