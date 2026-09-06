# Contributing

**English** · [Português (Brasil)](CONTRIBUTING.md)

Thank you for considering a contribution. This project is deliberately opinionated about process,
because the product's argument is rigour: a sloppy repository would invalidate it.

## Before you write code

Open an issue describing the problem before the solution. A change that arrives without a stated
problem is hard to review and harder to keep.

## Build and test

```bash
# JAVA_HOME must point at a JDK 25 installation
./gradlew build              # compiles, runs the tests, packages the plugin
./gradlew test               # the full suite
./gradlew test -PsecurityOnly # only the tests that sustain the inviolable principles
./gradlew verifyPlugin       # static verification against IDEA Community and Ultimate
./gradlew runIde -PsandboxProject="<path to a project>"   # sandbox IDE with a project open
```

Database tests start a real PostgreSQL through Testcontainers. Without Docker they declare
themselves skipped, and the rest of the suite still runs.

## The rules that are not negotiable

- **A failing security test blocks delivery.** It is never marked as a pending item, and it is never
  weakened to make a build green.
- **Nothing is written inside a user's repository.** Ever.
- **No secret in code, log, audit entry, error message or test fixture.**
- **Fail closed.** Ambiguous context, unresolved workspace or unparseable input ends in an explicit
  error, not in a best-effort guess.
- **The MCP surface stays in English.** Tool names, descriptions and client-facing errors are a
  contract read by an AI. Interface text is translated; the contract is not.
- **Static analysis is not sold as proof.** If you extend the risk classifier, keep the documentation
  honest about what it cannot catch.

## Documentation in two languages

Portuguese is the default for every document in the repository; English sits beside it, same name
with the `.en.md` suffix. Both versions carry the reciprocal language link right under the title. A
change in one language happens in the other in the same pull request — a half-translated document is
worse than a document in a single language.

## Code style

- Kotlin, four spaces, the surrounding code's naming and layer conventions.
- Comments explain **why**, not what. If a line needs a comment to say what it does, rewrite the line.
- No dead code, no stray `TODO`, no commented-out blocks.
- Every new behaviour that does not depend on the IDE comes with a test that does not need one.

## Commits and pull requests

- Commit messages are written in Portuguese, one line, direct: the minimum needed to understand what
  was done. No convention prefix (`feat:`, `fix:`, `chore:`) and no elaborate body.
- Good: `Cria o painel do Prumo na IDE` · `Apaga a senha da memória depois de usar`.
- No `wip` or `adjustments` commits in the history.
- A pull request describes the problem, the decision taken, and how it was verified. If you changed
  something that affects security, say which test proves it.
