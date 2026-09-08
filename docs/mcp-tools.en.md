# MCP tools

**English** · [Português (Brasil)](mcp-tools.md)

Every Prumo tool follows the same contract:

1. context resolved only through `CurrentWorkspaceContextService`;
2. the policy engine consulted before acting;
3. addressing by `repositoryId` / `datasourceId` / `packId` plus a relative path — **never an
   absolute path from the client**;
4. structured output, without secrets and without unnecessary absolute paths;
5. a sanitized audit entry;
6. an explicit, actionable error on ambiguity or violation.

Names use `_` because that is the format the platform's own tools use and the one AI clients accept.
The canonical name with a dot (`workspace.get_context`) is what appears in the audit trail.

---

## Diagnostics

### `prumo_diagnostics`
Confirms Prumo is active, which project the call resolved to, and whether that project is bound to
exactly one workspace. Use it before anything else when a client behaves oddly.

---

## Workspace

### `prumo_workspace_get_context`
The current workspace: id, name, type, the repository the open project belongs to, how many
repositories and documentation sources it has. Only the current workspace is ever visible.

### `prumo_workspace_get_policy`
What this workspace allows, decided by the policy engine — not a copy of the configuration flags.
Database actions are evaluated without a specific data source; the reason explains that.

### `prumo_workspace_get_repositories`
The bound repositories with role, access mode and normalized remote identity (`host/org/name`),
plus the free-text `description` the developer wrote about each one: what it is, what it is for
and which of its rules matter. Roles are `PRIMARY` (what is being built, including the open
project), `REFERENCE`, `LEGACY_REFERENCE` and `RELATED_COMPONENT`; they describe, they do not
grant — access mode does.
It also carries `excludedPaths`: the paths Prumo refuses to read, list or search in that repository.
This is not a request — the three repository tools enforce it. `.git` is always on that list, with no
configuration.
Never a local path, never a raw remote URL — a URL can carry an embedded token.

### `prumo_workspace_get_documentation_sources`
The attached documentation with authority level and whether Prumo can read it as text. PDF is
reported as catalogued and not extractable.

### `prumo_workspace_read_documentation`
Reads the content of a documentation source, addressed by its `documentationId`. When the source is a
folder, it also takes the path of a file inside it. Pages by line. Absolute paths, parent traversal
and any path that leaves the registered root are refused; a catalogue-only format such as PDF is
refused with an explanation.

### `prumo_workspace_prepare`
Validates the workspace and returns `READY`, `WARNING` or `ERROR` with one check per repository and
documentation source. A source whose content Prumo cannot read is reported as `WARNING`.
**Read-only**: it never runs `git pull`, `checkout`, `reset` or any mutation.

---

## Repository

All of these accept a `repositoryId` and default to the repository of the open project. They read
from disk, so unsaved editor changes are not included — for those, use the IDE's own tools.

### `prumo_repository_get_status`
Branch, upstream, ahead/behind counters and changed paths, from `git status --porcelain=v2`.

### `prumo_repository_get_branch`
Current branch with its upstream and distance, plus the local branch list.

### `prumo_repository_get_diff`
Added and deleted line counts per file and, when a `path` is given, the unified patch for that file.
Accepts `staged`. Never runs a mutating Git command.

### `prumo_repository_read_file`
Reads a text file by `path` relative to the repository root, with `firstLine` and `maxLines`.
Absolute paths and `..` are refused. Binary files are refused with an explicit message. Asking for a
directory is refused by saying it is a directory, and pointing at the tool that lists it — never by
saying it does not exist.

### `prumo_repository_search_text`
Literal text search inside a bound repository, optionally scoped to a subdirectory through `scope`.
Binary files and `.git` are never read. A `scope` that does not exist is refused rather than
answered with an empty search: a misspelled scope and a search that covered everything and found
nothing lead to opposite conclusions. A `scope` inside an excluded area still answers that it is
excluded, so the refusal never becomes an existence oracle over what the workspace chose not to
show.

### `prumo_repository_get_structure`
Directories and files of a bound repository. Use it to discover the layout of a repository that is
**not** the open project — for the open project the IDE's own tools already answer.

---

## IDE

### `prumo_ide_get_current_context`
Where the developer is right now: the file as `repositoryId` plus a relative path, caret line and
column, selection range, the chain of symbols containing the caret, language and module. If the open
file is not within reach, the answer is `insideWorkspace: false` — naming it would already be
telling about it — together with `reason`: `NO_FILE_OPEN` when no editor is selected,
`FILE_NOT_ON_DISK` when what is open lives in a jar, a scratch or a remote filesystem, and
`OUT_OF_REACH` when the file is not within reach of the workspace. Outside the bound repositories
and inside an excluded path both answer `OUT_OF_REACH`, on purpose: telling them apart would say
something is hidden there.

---

## Quality

### `prumo_quality_list_inspections`
The catalogue of inspections registered and enabled in the current profile of the open project —
what this IDE knows how to look for. **It runs no inspection and reads no file.** It accepts a
filter by language, severity and group, and returns the counts for the whole match before the
window of results.

Three limits the description states to the client, and that hold here:

- registered and enabled is **not** the same as applicable: the catalogue does not say what would
  run on a given file;
- the numbers describe this installation and its plugins, not the product — they change with the
  IDE edition and with what is installed;
- `shortName` is stable and always English, `displayName` follows the IDE language, and severity is
  not a closed vocabulary: a plugin may register its own.

The response opens by saying **which profile answered**. `profileScope` is `PROJECT` when a profile
is stored in `.idea/inspectionProfiles` and that profile answers — the ruleset the project agreed
on, and the one anyone cloning the repository gets. It is `APPLICATION` when no profile is versioned
with the project: the answer is then the configuration of that IDE installation, and another
developer may see something else.

The distinction needs the file because **the IDE materialises a `Project Default` profile in the
project even when the project brings none** — it is born as a copy of the application profile.
Asking the platform who manages the current profile answers "the project" in both cases; only the
versioned file separates an agreed ruleset from a local copy. A project in the old single-file
`.ipr` format has no such directory and reads as `APPLICATION`.

A filter value the catalogue does not know comes back in `unknownFilters` — so `severity: "WARNIG"`
is never confused with a filter that legitimately matched nothing. The accepted values come in
`knownValues`, and only for `severity` and `language`: group stays out because an installation has
hundreds of them, and whoever misspelled a group finds the valid ones by calling with no filter and
reading `byGroup`.

`maxResults` defaults to 50 and is capped at 200; a request outside that range is pulled into it,
without an error. **There is no paging:** with 1,577 rules registered in a common installation, the
way past the window is a narrower filter, not a next page — and `matchCount` still states the real
size of what matched.

Finding the problems of one specific file is a different job, and `get_file_problems`, from the
IDE's own MCP server, is the tool for it. Prumo does not replace it.

---

## Database

### `prumo_database_list_available`
The databases bound to this workspace: id, name, engine, access mode, default schema, the description
the developer wrote, and whether personal-data obfuscation is on, with a sentence on what to expect
from it. **Never** host, port, user, database name or credentials.

### `prumo_database_get_schema`
Schemas with how many tables and views each holds. System schemas are omitted.

### `prumo_database_list_tables`
Tables, views and materialized views, optionally restricted to one schema. Row counts are the
planner's estimate, stated as such.

### `prumo_database_describe_table`
Columns with types, nullability, defaults and comments; constraints and indexes as PostgreSQL itself
renders them. Structure only — no row is read.

### `prumo_database_execute_readonly`
One read-only statement. Accepts `SELECT`, `WITH … SELECT` and `EXPLAIN` without `ANALYZE`; refuses
DML, DDL, DCL, `CALL`, `COPY`, multiple statements and anything the parser could not read. Runs in a
read-only transaction that is rolled back. `maxRows` defaults to 100, ceiling 1000, with `truncated`
in the response. Columns whose name announces a secret come back masked.
A statement the server itself rejects — unknown column or table, missing `GROUP BY`, denied
privilege — comes back with the server's own message, naming what it refused.

---

## AI memory

A store of the AI clients' own. What goes in is **distilled by them** from sources already in reach
of this workspace, so that an expensive source need not be read again in a later session. It is not
portable: it lives on the machine, inside the workspace, and it is rebuildable from the sources.

It differs from packs in two ways: packs carry knowledge from outside and need the developer's
consent; the memory derives from what is already inside the boundary, and the AI writes it alone.

**What Prumo guarantees about a record:** where it came from, whether the source has changed since,
which client wrote it and when. **What it does not guarantee:** that the summary is faithful to the
source, or that it replaces the source. A record cites; it does not substitute.

Every read returns the freshness verdict beside the content:

| | |
|---|---|
| `FRESH` | the source is as it was when the record was written |
| `STALE` | the source changed, and the record may be wrong |
| `ORPHAN` | the source is no longer in reach of the workspace |

`FRESH` is about the **bytes of the source**, not about the record: it means nobody edited that
file, never that Prumo checked the text against it. A record pointing at a PDF — whose content Prumo
states it cannot extract — also comes back `FRESH`, because the file did not change.

The verdict is recomputed on every call, comparing size, modification time and SHA-256 digest of the
file. Any one of the three differing is enough for `STALE` — including when the change was elsewhere
in the file. That is deliberate: a false `STALE` costs one redistillation, a false `FRESH` hands over
wrong content as if it were good.

### `prumo_knowledge_remember`
Stores a distilled excerpt, naming the source it came from. **Prumo stamps the source itself**, never
accepting the stamp from the client: a stamp supplied by the AI would prove only what the AI said.
What is refused is a record **naming a source it cannot reach** — an id that is not in the
workspace, a path that does not exist inside it, or a path the developer excluded, and the three
refusals arrive distinct. Prumo stamps where the text came from; it does **not** check that the text
follows from there, and that judgement stays with whoever writes it.

### `prumo_knowledge_recall`
Finds what was already distilled, by text, by tag, by source or by freshness. It does not return the
text: it returns the list with provenance and verdict.

Text search is **by relevance**, over title, tags and body. The query is reduced to word stems before
the search, so an inflection other than the one written finds the record: "pagamentos" finds
"pagamento", and "payments" finds "payment". Portuguese and English are covered at once, because
nothing in a record declares which language it was written in — every record is indexed in both. Very
common words are dropped in both languages: searching for "de" or "the" returns nothing.

**Still deterministic and explainable**: it is word matching with stemming and BM25 ranking, never a
vector and never a model deciding what is similar. The weight of each field — title above tags, tags
above body — tilts the result without deciding it: a rare term in the body can outrank a common one
in the title. Tag, source and freshness remain exact filters.

A text query that matches nothing comes back with `searchedTerms`, which separates two situations
that used to look alike: an **empty** list means the whole query was common words and nothing was
left to look for; a **filled** one means those stems were searched and no record has them. The first
calls for rewriting the query; the second means the memory does not know about it.

Every result carries the `score` that put it there, comparable only against the others in the same
answer — it is relative position, not a grade. Ties are broken by id, so the same query always
returns the same order. A search with no text carries no `score`: there is nothing to rank.

The index is built and discarded inside the call. There is no second copy of the data to drift from
what is stored.

**A record whose source went out of reach is neither searched nor listed.** If the developer
excluded, after the distillation, the path the record came from, it stops existing for the search: it
is not ranked, it does not appear in the list, and it gives back no coordinate. What the answer
carries is `outOfReachCount`, how many such records the base holds — counted over the **whole base**,
like `storedCount`, and never over the query. A count that varied with the text searched would be an
oracle: swap the word, watch the number, and learn what is written inside what was excluded.

### `prumo_knowledge_read`
The full text of a record, with its provenance and freshness beside it. A `STALE` record is still
returned — what it says may still be useful — but the source is the truth.

**A record distilled from a path that is now excluded is refused**, text and coordinates alike. The
refusal names the record, not the path: naming the path would hand back, through the error message,
exactly what the exclusion takes out of reach. The refusal is recorded in the trail as `DENIED`.

### `prumo_knowledge_forget`
Removes a record. Immediate, without asking the developer. The source is untouched: only what was
distilled from it goes away.

---

## Packs

### `prumo_pack_list`
Installed packs with the capabilities each declared. Every response is marked `thirdParty: true`.
Accepts a `language` tag for titles and descriptions.

### `prumo_pack_search_knowledge`
Deterministic search over pack knowledge — text, title and tags. No embeddings, no model ranking:
the same question always returns the same result, and the LLM interprets it.

### `prumo_pack_get_knowledge`
The full text of one knowledge item, addressed by `packId` and `itemId`. File paths are never
accepted from the client.

### `prumo_pack_run_tool`
Runs a tool of an installed pack: a saved read-only query or a confined script. Queries pass through
the same layers as `execute_readonly`; scripts run confined, with the workspace policy and the
declared capability both required.

---

## Pack authoring

### `prumo_pack_get_authoring_spec`
Everything needed to write a pack Prumo will accept: schema, capability catalogue, security rules,
limits, where it gets installed, three complete examples and the most common reasons a pack is
refused. Read this before writing a pack.

### `prumo_pack_validate`
Checks a draft and returns what is wrong, where, and how to fix it. Free of side effects — call it
until the draft is valid.

### `prumo_pack_submit`
Puts the draft in the approval queue in the Prumo tool window. **It does not install, activate or
run anything.** Only the developer, on the consent screen, activates a pack.
