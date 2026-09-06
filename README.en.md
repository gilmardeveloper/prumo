# Prumo MCP

**English** · [Português (Brasil)](README.md)

[![CI](https://github.com/gilmardeveloper/prumo/actions/workflows/ci.yml/badge.svg)](https://github.com/gilmardeveloper/prumo/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![IntelliJ IDEA](https://img.shields.io/badge/IntelliJ%20IDEA-2026.1%2B-000?logo=intellijidea)](https://www.jetbrains.com/idea/)

**Turn your IDE into a standardized, isolated and auditable context source for MCP-capable AI
clients — Claude, Codex, Gemini and anything else that speaks the Model Context Protocol.**

Prumo is not an AI. It is the deterministic layer between your IDE, your code, your Git, your
documentation, your databases, your policies — and the AI client. It defines the boundary the
assistant may work within, and it enforces that boundary in code, not in a prompt.

---

## The problem

An AI client that helps with a real codebase needs context. Today that context is improvised:

- **The context is ad-hoc.** `CLAUDE.md`, `AGENTS.md`, a folder of notes, a copy-pasted schema. Each
  developer assembles their own, and none of it survives a machine change or reaches a colleague.
- **The boundary is a request, not a rule.** "Don't touch the legacy repository" is a sentence in a
  prompt. Nothing stops a tool from reading it, and nothing tells you afterwards that it did.
- **The database is the sharp edge.** Giving an assistant a connection string is easy. Giving it
  read-only access to exactly one database, with masked secrets, capped rows and an audit trail, is
  work that nobody does twice.
- **Multi-repository work leaks.** Modernizing a legacy system means reading one repository while
  writing another. The IDE knows about the project you opened, and nothing else.

## What Prumo does

Prumo introduces the **workspace**: an explicit boundary that says which repositories, which
documentation and which databases belong together, and what is allowed inside them.

- **Isolation by construction.** A workspace never references another. There is no query that,
  starting from one workspace, reaches the content of another — not a path, not a document, not a
  data source, not the audit trail.
- **Read-only that is enforced.** A repository bound as `READ_ONLY` stays read-only for every tool.
  A database bound as `READ_ONLY` refuses writes in the driver, in the session and in the statement
  classifier — and the read-only credential you configure remains the real barrier.
- **Nothing is written inside your repositories.** Every byte Prumo produces lives in an operating
  system directory, never in your project.
- **Everything is audited, nothing is copied.** The trail records what happened — tool, workspace,
  data source, statement type, row count — and never the data itself.
- **Team knowledge that travels.** A **Prumo Pack** carries documentation, saved queries and scripts
  between machines and colleagues, installed only after an informed consent screen that shows the
  capabilities it asks for, the risky patterns found in it and the scripts in full.

## Requirements

- IntelliJ IDEA **2026.1 or newer**, Community or Ultimate (the bundled MCP Server plugin, present
  since 2025.2, must be enabled)
- JDK 21 or newer to run the IDE; JDK 25 to build the plugin from source
- Windows or Linux
- PostgreSQL, optionally, for the database tools

## Install

**From a build:**

```bash
git clone https://github.com/gilmardeveloper/prumo.git
cd prumo
./gradlew buildPlugin
```

The plugin lands in `build/distributions/`. In the IDE: **Settings → Plugins → ⚙ → Install Plugin
from Disk…**, pick the ZIP, restart.

**Enable the MCP server** in **Settings → Tools → MCP Server**, and point your AI client at it. The
IDE exposes the server over SSE; Prumo contributes its tools to that same server instead of running
one of its own.

## Your first workspace

1. Open a project. In the right-hand tool bar, open **Prumo MCP**.
2. Click **Configure Workspace**, give it a name and a type. The open project is bound as the
   primary repository.
3. Click **Edit Workspace** to add what belongs together:
   - another repository as `LEGACY_REFERENCE` in `READ_ONLY`;
   - documentation — a folder of Markdown, a specification, a glossary;
   - a PostgreSQL data source, with the password going to the IDE password safe and **Test
     Connection** telling you what is wrong before your AI client finds out;
   - the policies, all denied by default.
4. Ask your AI client to call `prumo_workspace_get_context`. It gets the current workspace, and only
   the current workspace.

The tool window shows the workspace, its repositories with role and access mode, and the policies —
so the boundary is visible while you work, not buried in a settings file.

## The MCP surface

Twenty-five tools, documented one by one in [docs/mcp-tools.en.md](docs/mcp-tools.en.md):

| Group | What it answers |
|---|---|
| `prumo_workspace_*` | What is the current workspace, what does it allow, what belongs to it, is it ready |
| `prumo_repository_*` | Git status, branch and diff; read a file, search text, list structure — **including repositories that are bound but not open in the IDE** |
| `prumo_ide_get_current_context` | Where the developer is right now: file, caret, selection, enclosing symbols, module |
| `prumo_database_*` | Which databases, their schemas and tables, and one read-only statement at a time |
| `prumo_pack_*` | Installed packs, their knowledge, and the authoring cycle an AI client uses to propose new ones |

Prumo does not duplicate what the IDE's own MCP server already does — symbol search, inspections,
build, tests, refactoring, debugger. It adds what the native tools cannot: the workspace boundary,
repositories that are not the open project, Git state, isolated databases and portable team
knowledge.

## Security

The design assumptions are written down in [docs/security.en.md](docs/security.en.md), including what
Prumo does **not** protect against. In short:

- credentials live in the IDE password safe, never in a file, a log, an audit entry or an error
  message;
- every path from a client is `repositoryId` + relative path, canonicalized and validated against
  the repository root — absolute paths and `..` are refused;
- SQL is classified against an allow-list over the parsed statement, and runs inside a read-only
  transaction that is rolled back;
- columns whose name announces a secret come back masked, with no configuration required;
- pack scripts run with a confined working directory, a mandatory timeout, a minimal environment
  that does not inherit the IDE's variables, and limited output;
- a pack proposed by an AI client is never active until a human accepts it on the consent screen.

Every one of these is a named test in the security suite: `./gradlew test -PsecurityOnly`.

## Honest limitations of this MVP

- **PostgreSQL only.** The architecture accepts other engines without a rewrite; the MVP ships one.
- **Static analysis is signal detection, not proof.** The risk classifier finds known destructive
  patterns. A script written to hide what it does can pass. The real barriers are the granted
  capabilities, the confinement and your own reading of the consent screen.
- **Script confinement is not an OS sandbox.** The working directory and the environment are
  controlled; the process still runs as your user. A command with an absolute path reaches the disk.
- **PDF is catalogued, not extracted.** Prumo tells the client the document exists and does not
  pretend to read it.
- **Validated on Windows first.** Linux parity is a design requirement and is covered by tests and
  by CI, but the end-to-end script was run on Windows.
- **Interface in English and Brazilian Portuguese.** It follows the IDE language, and
  *Settings · Tools · Prumo MCP* overrides it when you want Prumo in a language the IDE is not
  using. The MCP surface stays in English by design: tool names and descriptions are a contract
  read by an AI, not interface text.

## Roadmap

Recorded, not implemented: other database engines, remote/enterprise MCP, a central workspace
registry, richer pack tooling. Nothing in this list is half-built in the codebase.

## Trying the whole thing

[docs/demo.en.md](docs/demo.en.md) walks the complete cycle end to end — workspace, boundary, database,
a pack written by an AI client and installed by a human — with what to observe at each step. It is
the script that decides whether this MVP is done.

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md). The short version: the flow is investigate → plan →
implement → review, the security suite blocks delivery, and a test that fails is never marked as a
pending item.

## License

[Apache License 2.0](LICENSE).
