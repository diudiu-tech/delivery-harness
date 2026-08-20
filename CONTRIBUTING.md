# Contributing

Thank you for helping improve Delivery Harness. This project is an MVP/reference implementation, so contributions should keep behavior explicit, testable, and honest about what is mocked or in memory.

## Before you start

- Read the [Code of Conduct](CODE_OF_CONDUCT.md).
- Use a GitHub Discussion or issue for design questions once the repository has a public home.
- Do not open a public issue for a vulnerability; follow [SECURITY.md](SECURITY.md).
- Never include real customer, courier, merchant, order, credential, or model-provider data.

## Development setup

Requirements: JDK 17+, Git, and the included Maven Wrapper.

```bash
./mvnw clean verify
```

Ollama is only required for manual end-to-end model calls. Unit tests must not require a running model or Docker daemon.

## Making a change

1. Create a focused branch from `main`, named `<type>/<subject>` — for example
   `fix/compensation-tier-selection` or `chore/remove-unreachable-code`.
2. Keep each change small and avoid unrelated formatting churn.
3. Add or update tests for observable behavior.
4. Update both `README.md` and `README.zh-CN.md` when user-facing behavior changes.
5. Record a decision in `docs/adr/` when a reviewer would reasonably ask
   "why didn't you just…?" — deleting a component, declining a technology, or
   accepting a known limitation all qualify. Routine implementation choices do
   not.
6. Run `tools/check-format.sh` and `./mvnw clean verify` before opening a pull
   request. Together these are exactly what CI runs.
   `tools/check-format.sh --fix` repairs formatting failures in place.

Java source follows the existing four-space style and `.editorconfig`. Prefer constructor injection, immutable values where practical, typed request/response objects, and explicit bounds on user-controlled collections or text.

## Commits

Use [Conventional Commits](https://www.conventionalcommits.org/). The subject
line says what changed; the body says why it needed to change. A reviewer
reading `git log` a year from now has the diff already — what they lack is the
reason.

```text
fix(knowledge): reject invalid chunk overlap
docs(readme): clarify in-memory storage
test(llm): cover fenced JSON extraction
```

Prefer several small commits that each build over one large one. Where a change
genuinely cannot be split without producing a commit that does not compile, say
so in the message rather than splitting it artificially.

## Quality gates

| Gate | Where | Fixing a failure |
| --- | --- | --- |
| Formatting | `tools/check-format.sh`, before the build | `tools/check-format.sh --fix` |
| Tests | Surefire, `test` phase | Reports upload as a CI artifact on failure |
| Coverage | JaCoCo on `harness-core`, `verify` phase | HTML report uploads as a CI artifact on every run |
| Dependencies | `dependency-review` on pull requests | Replace the dependency, or justify it in the pull request |
| CodeQL | Scheduled and on pull requests | Fix the finding or annotate it |

The formatting gate is a shell script rather than a build plugin. It enforces
the four rules `.editorconfig` already declares — no trailing whitespace, a
final newline, LF endings, no leading tabs in Java or XML — and nothing else.
Those rules are worth enforcing; a plugin dependency, its version, and its
configuration surface are not worth carrying to enforce them.

`jacoco.core.line.minimum` in the root POM is a ratchet. Raise it when a branch
lands above it. Never lower it to make a build pass — if coverage dropped, that
is the finding.

The current floor is `0.25`, calibrated against a measured `0.2557` on
`harness-core`. That figure is low mainly because the end-to-end tests live in
`harness-api`, so the `harness-core` lines they cover are not attributed to
this module; `jacoco:report-aggregate` is the fix. Read the number as "nobody
deleted the unit tests", not as a statement about quality.

To see the current number without the gate blocking you:

```bash
./mvnw clean verify -Djacoco.core.line.minimum=0
open harness-core/target/site/jacoco/index.html
```

## Pull requests

A pull request should explain the problem, the chosen approach, test evidence, security/data implications, and documentation changes. Mark mock implementations and planned integrations clearly. Maintainers may ask for a smaller scope or additional tests before merging.

By submitting a contribution, you agree that it may be distributed under the repository's [MIT License](LICENSE).
