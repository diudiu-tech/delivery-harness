# Architecture Decision Records

Short, immutable records of decisions that shaped this codebase — in particular
decisions to *not* build something, which are otherwise invisible to a reader.

Format: [MADR](https://adr.github.io/madr/)-lite. One file per decision,
numbered sequentially, never edited after acceptance. Supersede instead of edit.

| ADR | Title | Status |
| --- | --- | --- |
| [0001](0001-delete-unreachable-code-before-adding-features.md) | Delete unreachable code before adding features | Accepted |
| [0002](0002-rule-engine-owns-the-compensation-amount.md) | The rule engine owns the compensation amount, not the model | Accepted |
| [0003](0003-no-vector-retrieval-at-this-corpus-size.md) | No vector retrieval at this corpus size | Accepted |
| [0004](0004-three-maven-modules-instead-of-nine.md) | Three Maven modules instead of nine | Accepted |

## When to write one

Write an ADR when a reviewer would reasonably ask "why didn't you just…?".
Deleting a component, choosing not to adopt a technology, and accepting a
known limitation all qualify. Routine implementation choices do not.
