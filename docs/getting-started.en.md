# Getting started

**English** · [Português (Brasil)](getting-started.md)

From nothing to your AI client answering with the context of your workspace. It takes about fifteen
minutes. Every step says **what to observe** — a step you cannot check is not a step.

---

## 1 · Requirements

- IntelliJ IDEA **2026.1 or newer**, Community or Ultimate, with the **MCP Server** plugin enabled —
  it ships with the IDE since 2025.2
- The Prumo plugin installed
- An MCP-capable AI client: Claude, Codex, Gemini or another
- PostgreSQL, only if you are going to use the database tools

## 2 · Install the plugin

There is no JetBrains Marketplace release yet, so installation is from the ZIP:

1. **Settings → Plugins → ⚙ → Install Plugin from Disk…**
2. pick the Prumo ZIP;
3. restart the IDE when it asks.

To build the ZIP from source, see the [README](../README.en.md).

**Observe:** after the restart there is a **Prumo MCP** tool window on the right-hand bar. If it is
not there, the plugin did not load — check under **Settings → Plugins** that it is enabled.

## 3 · Turn on the IDE's MCP server

Prumo **does not run a server of its own**. It contributes its tools to the MCP server the IDE
already has, so that is the server that has to be running.

1. **Settings → Tools → MCP Server**
2. tick **Enable MCP Server**.

Two sections of that screen matter:

- **Project Clients Auto-Configuration** — one button per known client, which writes the
  configuration into the project file for you. It is the shortest path for Claude Code and Codex.
- **Manual Client Configuration** — the **Copy SSE Config**, **Copy HTTP Stream Config** and **Copy
  Stdio Config** buttons, which put the address and the shape that transport expects on your
  clipboard.

**Observe:** the screen shows the port the IDE published the server on. The default is `64342`, so
the SSE address reads `http://127.0.0.1:64342/sse`. The port belongs to the installation, not to the
project, and it changes when more than one IDE is open — check the number on that screen before
pasting it anywhere.

## 4 · Connect your AI client

If your client is in the IDE's auto-configuration list, click its button and skip to step 5. What
follows is the manual path, for a client that is not on that list or for configuration you want in
your profile rather than in the project.

In all of them, `<URL>` is the address the IDE copied.

### Claude Code

```bash
claude mcp add --transport sse prumo <URL>
```

Use `http` instead of `sse` if you copied the HTTP Stream configuration. Add `--scope user` to make
it apply to all your projects.

Check it with:

```bash
claude mcp list
```

### Codex

Codex keeps its servers in `~/.codex/config.toml`:

```toml
[mcp_servers.prumo]
url = "<URL>"
```

An older Codex only sees local servers, and needs the remote client turned on above the server
entry:

```toml
[features]
rmcp_client = true
```

On older builds still, that key was called `experimental_use_rmcp_client` and lived at the top of
the file. If the server does not show up, upgrading Codex is faster than finding out which of the
two your version reads.

### Gemini CLI

Gemini reads `~/.gemini/settings.json`, or `.gemini/settings.json` at the project root:

```json
{
  "mcpServers": {
    "prumo": {
      "httpUrl": "<URL>"
    }
  }
}
```

`httpUrl` is for the HTTP Stream transport. If you copied the SSE configuration, the key is `url`
instead of `httpUrl`.

### A client that only takes a command

Many clients have no field for a URL: they ask for a command to start, a list of arguments, the
environment variables and a working directory. In those, the thing that talks to the IDE is the
`mcp-remote` bridge, stdio on one side and SSE on the other. Fill it in like this:

| Field | What to put in |
|---|---|
| Command to start | `npx` |
| Arguments | one per field, in this order: `-y`, `mcp-remote`, `http://127.0.0.1:64342/sse`, `--allow-http`, `--transport`, `sse-only` |
| Environment variables | nothing |
| Environment variable forwarding | nothing |
| Working directory | nothing |

Replace the port with the one the IDE screen shows. `--allow-http` is there because the address is
`http` and not `https` — nothing leaves your machine. `--transport sse-only` stops the bridge from
trying HTTP Stream first and taking its time to fall back to SSE. Node has to be installed, since
`npx` comes with it.

In a client that takes JSON instead of fields, the same thing reads:

```json
{
  "mcpServers": {
    "prumo": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        "http://127.0.0.1:64342/sse",
        "--allow-http",
        "--transport",
        "sse-only"
      ]
    }
  }
}
```

### Another client

Any MCP-capable client works. What it needs to know is the transport and the address, and both come
out of the copy buttons on the IDE screen. Prumo asks for no key, no token and no account.

**Observe:** ask the client for the tool list. The ones starting with `prumo_` are this plugin's;
the rest are JetBrains' own, and they are still there. If no `prumo_` tool shows up, the server came
up without the plugin — go back to step 2.

## 5 · Configure the workspace

Without a workspace Prumo exposes nothing, and says so to whoever asks. That is failing closed, not
a defect.

1. Open the project and, on the right-hand bar, the **Prumo MCP** tool window.
2. Click **Configure Workspace**, give it a name and a type. The open project is bound as the
   primary repository.
3. Click **Edit Workspace** and add what belongs together:
   - **another repository** — role *Legacy reference*, access *Read only*, to read the old system
     while you write the new one;
   - **documentation** — a folder of Markdown, a specification in PDF, a spreadsheet, a glossary;
   - **a PostgreSQL data source** — host, port, database, user and password, with **Test
     Connection** telling you what is wrong before your AI client finds out;
   - **the excluded paths** of each repository, which Prumo refuses to read, list and scan;
   - **the policies**, all denied by default.

**Observe:** a new binding is born read-only before you touch anything, and the password goes to the
IDE password safe — the workspace's `datasources.json` never holds it.

If you are going to use meaning-based documentation search, this is also where it is enabled: on the
**Knowledge** tab, the button that downloads the local model, about 130 MB, and the same place it is
removed from. Without the model search still works by word, and the answer says so.

## 6 · The first-interaction prompt

The MCP server carries no presentation text: between connecting and the first call, the client gets
nothing but tool names and descriptions. This prompt shortens the discovery and sets the working
rules. Paste it as the first message of the session, or keep it in your client's instructions file.

```text
You are connected to the MCP server of my IntelliJ IDE. The tools prefixed prumo_ belong to the
Prumo plugin, which defines the boundary of what you may see in this system.

Before answering anything about the project, do this, in order:

1. prumo_diagnostics — confirm Prumo answered and which project the call resolved to.
2. prumo_workspace_prepare — see whether the workspace is ready and what carries a warning.
3. prumo_workspace_get_context, get_policy, get_repositories and get_documentation_sources — the
   boundary: what exists, what is read-only, what is excluded and what is allowed.

Rules for the rest of the session:

- Address a file by repositoryId plus a relative path. Absolute paths are refused.
- Before inferring a rule from the code, search the attached documentation with
  prumo_workspace_search_documentation and cite the coordinate that comes back.
- If that search returns pendingSources above zero, the shelf is still being indexed: repeat the
  search before concluding there is nothing there.
- If semanticAvailable comes back false, the search was by word only — try synonyms before giving
  up.
- Never ask for a whole document: search for the passage and, if you need more, read its
  neighbourhood from the line the answer gave you.
- Call prumo_knowledge_recall before re-reading an expensive source, and store what you distil with
  prumo_knowledge_remember, always naming the source it came from.
- A refusal from an excluded path or from policy is the boundary working, not an error: do not work
  around it, do not try another path to the same file, and tell me what was refused.

End this first answer by naming the workspace you saw, how many repositories and documentation
sources it has, and which policies are granted.
```

**Observe:** the answer has to name *your* workspace. If the client describes something else, or
says it found no workspace at all, the call resolved to another open project — close the ones you do
not need and try again.

## 7 · Check that it works

Ask the client, one sentence each:

| Ask for this | What it confirms |
|---|---|
| call `prumo_diagnostics` | the plugin is alive and the project was identified |
| call `prumo_workspace_get_context` | the current workspace, and only it, is visible |
| read a file from the reference repository | a repository that is not open in the IDE answers |
| search the documentation for a subject | the passage comes back with the coordinate to cite |
| ask for an `UPDATE` on the database | the refusal arrives as a refusal, and lands in the trail as denied |

The **Activity** tab of the tool window shows the trail: one line per call, with the tool, the
outcome and who called. Content that was read never appears there.

## 8 · Spend fewer tokens

Prumo also exists so that the answer fits. Context that does not fit the model's window does not
help, and what does fit is paid for in tokens every new session.

Three habits change the bill, and the prompt in step 6 already asks for them:

- **Search for the passage before reading the document.** The eSocial manual runs to 413 pages,
  about 246 thousand tokens of extracted text. The answer you want usually fits in three paragraphs,
  and that is what `prumo_workspace_search_documentation` returns, with the coordinate to cite.
- **Read the neighbourhood, not the file.** `prumo_workspace_read_documentation` and
  `prumo_repository_read_file` paginate by line. In a binary format, neighbouring lines from one
  origin come grouped into one range, so a coordinate never costs more than the text it
  addresses.
- **Do not distil twice.** What was expensive to understand goes into the memory with
  `prumo_knowledge_remember` and comes back next session through `recall`, which returns the list
  with provenance and freshness — the text only arrives when you ask for one record with `read`.

The bigger waste is not in the call but in a boundary that is too wide: a repository that does not
belong to the work, a build folder bound as documentation, pages nobody cites. A path excluded in
step 5 is a token that never gets spent.

## When it does not work

| Symptom | Where to look |
|---|---|
| The client lists no tools at all | The MCP server is off under **Settings → Tools → MCP Server**, or the client points at another port |
| It lists the JetBrains tools but no `prumo_` ones | The plugin is not enabled, or the IDE was not restarted after installing it |
| "could not determine which open project this call refers to" | More than one project is open and the client did not say which — name the project path in the call |
| "this project is not bound to any Prumo workspace" | Step 5 is missing, or the open project is not the one you bound |
| Documentation search comes back empty | Check `pendingSources` and `semanticAvailable` in the answer itself before concluding there is nothing there |

---

For the whole cycle — boundary, database, a pack written by an AI client and installed by a human —
follow [docs/demo.en.md](demo.en.md). The tools are described one by one in
[docs/mcp-tools.en.md](mcp-tools.en.md).
