# Security model

**English** · [Português (Brasil)](security.md)

This document states what Prumo protects, how, and — as importantly — what it does not protect
against. A security claim without its limits is marketing.

## Threat model

Prumo sits between an AI client and a developer's machine. The actors are:

| Actor | Assumed to be | Threat considered |
|---|---|---|
| The AI client | Non-malicious but **unpredictable**: it composes calls, retries, and follows text it read somewhere | Reading or changing what it was not meant to; leaking content of one workspace into another |
| Prompt content and repository content | **Untrusted input** | Instructions embedded in a file, a table or a document that try to redirect the assistant |
| A Prumo Pack from a colleague | **Untrusted code**, installed deliberately | Destructive commands, credential theft, reaching another workspace, download-and-execute |
| The developer | Trusted, but human | Accepting something they did not read, misconfiguring a boundary |

Out of scope: an attacker with control of the machine, a malicious IDE plugin running alongside
Prumo, or a compromised JetBrains distribution. If any of those hold, nothing in this document helps.

## Guarantees, and how each is enforced

**Workspace isolation.** A workspace never references another. Storage is addressed by workspace id,
so there is no query that walks from one workspace to another's repositories, documentation, data
sources, packs or audit trail. Isolation comes from the shape of the access, not from a check
someone can forget to call.

**Path containment.** A client never sends an absolute path: it sends `repositoryId` plus a relative
path. Resolution is validated twice — over the normalized path and, when the target exists, over the
real filesystem path — because normalization alone does not see symlinks, junctions or mount points.
The `.git` directory is never read as project content.

**Read-only repositories.** `READ_ONLY` is a property of the binding, checked by the policy engine
before any operation. The MVP's repository surface is read-only in its entirety, and a test pins the
registered tool names so that adding a writing tool is a visible change.

**Read-only databases, in layers.** In order: (1) the read-only credential you configure in
PostgreSQL — the real barrier; (2) the JDBC connection and session marked read-only; (3) an explicit
`SET TRANSACTION READ ONLY`, applied even when the data source is bound as `READ_WRITE`, because
`execute_readonly` is read-only by definition; (4) statement classification over the parsed syntax
tree, accepting only `SELECT`, `WITH … SELECT` and `EXPLAIN` without `ANALYZE`; (5) a row ceiling
and a query timeout; (6) a sanitized audit entry. The transaction is always rolled back.

**Credential protection.** Passwords live in the IDE password safe, keyed by workspace and data
source. They travel in `CharArray` and are wiped after use. They never appear in a profile, a JSON
file, a JDBC URL, a log line, an audit entry or an error message. The only moment a password becomes
a `String` is the `Properties` map the JDBC API requires, inside a function that clears it.

**Secrets in query results.** A column whose name announces a secret — `password`, `senha`, `token`,
`api_key`, and others — comes back masked with **no configuration required**. You may mask more
columns; you cannot unmask those.

**Nothing written inside your repositories.** Every artifact Prumo produces goes to an operating
system directory. A test proves that installing a pack writes nothing into a project directory.

**Pack consent.** A pack is never active before a human accepts it. The consent screen shows origin,
version, checksum, the capabilities in plain language, every finding with the exact snippet that
produced it, and the scripts in full. Destructive findings require item-by-item acknowledgement,
never pre-checked. A `BLOCKED` pack has no accept button and no parameter that installs it.

**Script confinement.** Commands are argument lists, never shell strings. The working directory is
inside the pack. The environment is not inherited from the IDE — a minimal `PATH` plus what the pack
declared. Standard input is closed, output is capped, the timeout kills the process tree.

**Audit without duplication.** The trail records tool, workspace, repository, data source, pack,
statement type, row count, outcome and duration. It never records file content, query results, SQL
text or parameter values: an audit that copies the data becomes a second copy of what it was meant
to protect.

## What Prumo governs, and what it does not

Prumo bounds **Prumo's tools**. The IDE's MCP server is not Prumo's: it belongs to the platform, and
it serves other tool families to the same client, with their own and wider boundaries.

Measured in the field against a real installation: the IDE's own tools refuse by **destination** —
what falls outside "project, library, and SDK roots" — and library and SDK roots live outside the
project. They returned content from `C:\Program Files\Java\jdk-25` and from the user's Maven
repository through `..` paths, which Prumo refuses by **form**, before looking at the destination.
The same list includes a tool that runs terminal commands.

The effective reach of a connected client is therefore the **union** of the families, not the
intersection, and the most permissive one sets the ceiling. What Prumo guarantees is that **through
its own tools** nothing outside the workspace is reached, and that the reference repository, the
documentation and the database exist only through it. Bounding a whole session means disabling the
other families in the AI client, and that is outside this plugin's reach.

## The limit of masking

Masking decides by the **originating column**, read from the driver metadata, and not by the alias
the client chose: `SELECT senha AS num_matricula` comes back masked. A computed column has no
originating column in the metadata; there Prumo reads the select list and masks the column whose own
expression touches a sensitive name. When the list cannot be mapped safely — `*` expansion, a
statement that is not a simple `SELECT` — every computed column is masked: masking too much, which
is the accepted direction.

## Personal data obfuscation

An authentication secret is replaced whole. **Personal data is partially hidden** — enough that it
cannot be reconstructed, little enough that the AI still understands the field: a CPF comes back as
`123.***.789-**`, a phone as `(85) ****-4321`, a name as `Maria S. S.`, an e-mail keeps its domain,
and a birth date keeps its year, so age brackets and age-based rules stay analysable.

Three decisions hold this up:

- **A check digit is never shown.** It is a function of the other digits: it adds no business
  information, and it lets a guess from another source be verified.
- **A column is recognised by every name that identifies it** — the query label, the origin column
  and, for a computed column, the identifiers in its own expression. An alias does not switch the
  protection off.
- **The data is obfuscated, never the metadata.** Column name, type, comment, constraint and index
  come back intact. The AI needs the full structure to write correct SQL; what it does not need is
  the person's document.
- **Obfuscation happens on the way out**, after the database has resolved the query. Joins, grouping,
  filtering and ordering keep operating on the real value.

The switch lives on the database binding form and is **on by default**: a freshly bound database
protects without anyone remembering to enable it. Turned off, personal data is handed to the AI
exactly as stored — the password and token mask still applies, being a separate guarantee.

The limit is known: an obfuscated value is **not a key**. Two values that differ only in the hidden
digits come back identical, and equality on the way out does not prove equality at the source.

What masking does **not** do is prevent inference. A query using the sensitive column in a predicate
— `WHERE senha = 'guess'` — either returns rows or does not, and that confirms the value without ever
displaying it. Masking is about what leaves, not about what can be deduced. The barrier against that
is a least-privilege read-only credential, not Prumo.

## What Prumo does *not* protect against

- **Static analysis is not proof.** The risk classifier detects known destructive patterns. A script
  written to obfuscate what it does will pass. Treat it as a spotlight, not a wall.
- **Script confinement is not an OS sandbox.** The process runs as your user, with your permissions.
  A command with an absolute path reaches the whole disk. Portable filesystem sandboxing does not
  exist in the JVM; the barrier before it is your reading of the consent screen.
- **A read-only credential is your responsibility.** Prumo's layers reduce the blast radius of a
  mistake; they do not turn a superuser connection into a safe one.
- **The AI client can still be socially engineered** by content it reads. Prumo limits *what* it can
  reach, not what it concludes.
- **PDF is catalogued, not parsed.** Nothing inside a PDF is analysed.

## Reporting a vulnerability

See [SECURITY.md](../SECURITY.md).

## Verifying the claims

Every guarantee above is a named test:

```bash
./gradlew test -PsecurityOnly
```

`SecurityCoverageTest` maps each principle to the tests that sustain it and fails if one is removed.
A failing security test blocks delivery; it is never recorded as a pending item.
