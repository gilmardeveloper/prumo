# MCP tools

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
The bound repositories with role, access mode and normalized remote identity (`host/org/name`).
Never a local path, never a raw remote URL — a URL can carry an embedded token.

### `prumo_workspace_get_documentation_sources`
The attached documentation with authority level and whether Prumo can read it as text. PDF is
reported as catalogued and not extractable.

### `prumo_workspace_prepare`
Validates the workspace and returns `READY`, `WARNING` or `ERROR` with one check per repository and
documentation source. **Read-only**: it never runs `git pull`, `checkout`, `reset` or any mutation.

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
Absolute paths and `..` are refused. Binary files are refused with an explicit message.

### `prumo_repository_search_text`
Literal text search inside a bound repository, optionally scoped to a subdirectory. Binary files and
`.git` are never read.

### `prumo_repository_get_structure`
Directories and files of a bound repository. Use it to discover the layout of a repository that is
**not** the open project — for the open project the IDE's own tools already answer.

---

## IDE

### `prumo_ide_get_current_context`
Where the developer is right now: the file as `repositoryId` plus a relative path, caret line and
column, selection range, the chain of symbols containing the caret, language and module. If the open
file belongs to no repository of this workspace, the answer is only `insideWorkspace: false` —
naming a file outside the boundary would already be telling about it.

---

## Database

### `prumo_database_list_available`
The databases bound to this workspace: id, name, engine, access mode, default schema. **Never** host,
port, user, database name or credentials.

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
