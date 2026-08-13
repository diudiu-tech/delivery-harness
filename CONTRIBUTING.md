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

1. Create a focused branch from `main`.
2. Keep each change small and avoid unrelated formatting churn.
3. Add or update tests for observable behavior.
4. Update both `README.md` and `README.zh-CN.md` when user-facing behavior changes.
5. Run `./mvnw clean verify` before opening a pull request.

Java source follows the existing four-space style and `.editorconfig`. Prefer constructor injection, immutable values where practical, typed request/response objects, and explicit bounds on user-controlled collections or text.

Commit messages should be concise and may use Conventional Commit prefixes, for example:

```text
fix(knowledge): reject invalid chunk overlap
docs(readme): clarify in-memory storage
test(llm): cover fenced JSON extraction
```

## Pull requests

A pull request should explain the problem, the chosen approach, test evidence, security/data implications, and documentation changes. Mark mock implementations and planned integrations clearly. Maintainers may ask for a smaller scope or additional tests before merging.

By submitting a contribution, you agree that it may be distributed under the repository's [MIT License](LICENSE).
