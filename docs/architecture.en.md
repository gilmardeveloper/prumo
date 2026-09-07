# Architecture

**English** · [Português (Brasil)](architecture.md)

Prumo is an IntelliJ Platform plugin that contributes tools to the IDE's bundled MCP server. It has
one architectural rule that explains most of the rest: **the part that decides what an AI client may
see does not depend on the IDE**, so it can be tested without one.

## Layers

```
┌──────────────────────────────────────────────────────────────────────────┐
│ toolsets/            MCP adapter — thin. Resolves the project, delegates. │
├──────────────────────────────────────────────────────────────────────────┤
│ ui/                  Tool window, workspace editor, consent screen.       │
├──────────────────────────────────────────────────────────────────────────┤
│ ide/                 The only place that touches Project, PSI, Git4Idea.  │
├──────────────────────────────────────────────────────────────────────────┤
│ workspace/ policy/ repository/ datasource/ pack/ audit/ storage/ quality/ │
│                      The deterministic core. No IntelliJ types.           │
├──────────────────────────────────────────────────────────────────────────┤
│ platform/            Operating system, directories, process execution.    │
└──────────────────────────────────────────────────────────────────────────┘
```

Everything below `ide/` is plain Kotlin. That is why 243 tests run in seconds without starting an
IDE, and why the boundary guarantees are verifiable rather than argued.

## The one path to context

Every tool goes through the same contract, in `toolsets/PrumoToolCall.kt`:

1. **Resolve the project.** The IDE's MCP server is one server for the whole application and serves
   several open projects. A project chosen by convenience when that identification fails would mean
   exposing one workspace instead of another, so the absence of a project is always an error.
2. **Resolve the workspace**, through `CurrentWorkspaceContextService` — the single resolver. Not
   configured is an error; bound to more than one workspace is an error. Prumo does not choose.
3. **Resolve the target** — a repository or a data source — *inside* the workspace. An identifier
   that does not belong here is refused, not searched for elsewhere.
4. **Ask the `PolicyEngine`.** No tool decides permission on its own, and no instruction given to the
   LLM substitutes for this evaluation.
5. **Do the work**, then **record the audit** with the real outcome.
6. **Fail closed and explicit.** Ambiguity, violation and unparseable input all end in an actionable
   error, never in a best-effort guess.

Because the contract lives in one function, a new tool cannot implement half of it by accident.

## How a project becomes a workspace

A repository is identified by a **fingerprint**, not by its path: the normalized Git remote when
there is one, the directory name otherwise. A developer who moves `C:\repos` to `D:\workspace`, or
clones the same repository on another machine, keeps the binding. The alternative — writing a marker
file inside the repository — is forbidden by the "nothing inside your repositories" rule.

Two workspaces claiming the same project is a configuration error, and Prumo says so instead of
picking one.

## Where things are stored

| What | Where |
|---|---|
| Workspaces, packs, audit | `%LOCALAPPDATA%\PrumoMCP\` on Windows, `$XDG_DATA_HOME/prumo-mcp/` on Linux |
| Credentials | The IDE password safe, addressed by workspace + data source |
| Your project | **Nothing.** Not one byte. |

## Decisions, dated

| Date | Decision | Why |
|---|---|---|
| 2026-09-04 | Contribute to the IDE's MCP server instead of running our own | One server, one consent, one place for the user to configure |
| 2026-09-04 | Read `.git/config` directly for remote and root | Same behaviour on Windows and Linux, testable without the IDE |
| 2026-09-04 | Tool names use `_`, not `.` | The platform's own tools do, and clients validate names against `[A-Za-z0-9_-]` |
| 2026-09-05 | Bundle the PostgreSQL driver | Database Tools exists only in Ultimate; Prumo must work in Community |
| 2026-09-05 | Git state through the bundled Git plugin | Uses the executable the user already configured; no second Git implementation inside the plugin |
| 2026-09-05 | No connection pool | A pool keeps authenticated connections alive between calls: secrets in memory for longer, session state surviving a query |
| 2026-09-05 | JSqlParser for statement classification | Pure Java, Apache-2.0/LGPL, 1.2 MB. Writing our own SQL recognizer is how most security bypasses happen |
| 2026-09-05 | Gradle build cache disabled | It restored stale test output after an ABI change: 34 tests failed against old bytecode, and worse, some had passed |
| 2026-09-05 | Interface bilingual, MCP surface English-only | Tool names and descriptions are a contract read by an AI; interface text is for a human |
| 2026-09-05 | Interface language resolved by Prumo, not only by the IDE | The IDE language is the default; forcing one requires loading the bundle for an explicit locale, because the platform's `<resource-bundle>` and the internal `getResourceBundleLocalized` follow the IDE |

## Testing strategy

- The deterministic core is tested directly, without an IDE.
- The database layers are tested against a real PostgreSQL through Testcontainers. Without Docker,
  those tests declare themselves skipped — the rest of the suite still means something.
- Script confinement is tested against the real operating system, with commands chosen per OS so the
  same guarantees are checked on Windows and Linux.
- Two tests read the project's own source: one pins the registered MCP tool names, another proves
  that the authoring toolset contains no installation path.
- `SecurityCoverageTest` maps each inviolable principle to the test that sustains it, and fails when
  one disappears.
