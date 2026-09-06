# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0-rc.10] - 2026-09-06

### Fixed

- **The diff tool never enforced excluded paths, and 0.1.0-rc.9 started claiming it did.** Exclusion
  was imposed on reading a file, searching text and listing structure — the fourth repository tool
  was missed. A patch carries the changed lines themselves, so an excluded file with pending changes
  handed over by difference what the other tools refuse to hand over by reading. The rc.9 rewrite of
  the tool descriptions asserted the tool "honours the paths excluded for this repository", which was
  not true of the code: a guarantee written into a contract an AI reads is a guarantee, and this one
  was empty. Changed files under an excluded path no longer appear, asking for the patch of an
  excluded path is refused, and the match rule now lives in one place instead of being restated —
  restating it is how exclusion was once bypassed by changing the case of a name. A test now fails
  when a description promises the exclusion and the code does not consult it.

## [0.1.0-rc.9] - 2026-09-06

### Added

- **Personal data comes back partially hidden.** A CPF now reads `123.***.789-**`, a phone
  `(85) ****-4321`, a name `Maria S. S.`; an e-mail keeps its domain and a birth date keeps its year,
  so age-based rules stay analysable. A check digit is never shown — it is a function of the other
  digits, so it adds nothing and lets a guess from another source be verified. Column names, types
  and comments are never altered: the AI needs the full structure to write correct SQL, not the
  person's document. The switch lives on the database binding and is on by default. Obfuscation is
  applied on the way out, so joins, grouping and filtering keep operating on the real value — but an
  obfuscated value is not a key, since two values differing only in hidden digits come back equal.
- **A database binding can carry a description**, handed to the AI client the way a repository
  description already was. A field evaluation found a database labelled `dev` that was in fact a copy
  of production: two AI agents suspected it and neither could confirm it, and one recorded that it
  could not tell whether it was reading real people's data.
- **`prumo_workspace_read_documentation`** reads the documentation attached to a workspace. Until now
  Prumo announced a source as text-extractable and offered no way to extract it — an evaluator put it
  plainly: the specification was cited in the code, listed in the workspace, and impossible to open.
  Absolute paths, parent traversal and any path leaving the registered root are refused.

### Changed

- **Tool descriptions now say when to use each tool.** The IDE's MCP server transmits no
  presentation text, so between connecting and the first call a client sees only names and
  descriptions — and Prumo's sat at the end of a 69-tool list, describing themselves while the native
  tools instructed ("Use this tool to…", "You MUST prefer this tool over…"). One blind evaluator
  never noticed Prumo existed: it did the whole job with the IDE's own tools, reported that database
  access was missing while `prumo_database_*` sat unused in the same session, and — without the
  workspace context — read a stale README and reported the wrong Java version. One description was
  actively handing the client away, telling the AI that for the open project the IDE's own tools
  already answer; that sentence is gone, and a test now fails if any description cedes preference
  again. `prumo_workspace_prepare` announces itself as the first call and explains what a workspace
  is.
- **Workspace policy no longer declares the database off limits.** Database actions were evaluated
  without a datasource, which is a situation a real query never hits, and the answer came back
  denied. Two blind evaluators concluded the database was closed and one nearly finished its work
  without ever querying it. Actions are now decided per bound database, and the answer names which
  ones allow what.

### Fixed

- **A plain count was blanked out for standing next to a secret.** `SELECT count(*) AS total,
  max(txt_senha) AS x` returned `[masked]` for the count, which is not a secret at all; the same
  count alone returned the number. The mask now falls on the computed column whose own expression
  touches a sensitive name, and still covers every computed column when the select list cannot be
  mapped safely.
- **A database refused by the domain no longer vanishes without a word** when saved. The repository
  binding got that protection in 0.1.0-rc.8; the database binding never had it.

## [0.1.0-rc.8] - 2026-09-06

### Fixed

- **A repository with excluded paths was silently dropped instead of being bound.** The dialog said
  the paths are read from the repository root, so writing `/FONTES/curl` is the natural thing to do —
  and the domain refused any entry starting with a slash. The refusal surfaced nowhere: the dialog
  closed on OK and the repository simply never appeared in the list, while the same repository bound
  fine with only a description. A leading slash, a trailing slash and Windows backslashes are now
  normalised away on entry, so `/FONTES/curl` and `FONTES/curl` name the same thing — which is what
  the matcher already needed, since the stored form was the only one it could ever match. What has no
  relative reading — `..` and a disk drive — is still refused, now with a message naming the path.
  A binding the domain rejects for any other reason also reports it on screen instead of vanishing.

## [0.1.0-rc.7] - 2026-09-06

### Changed

- **Configuring a workspace whose name is already taken now offers to overwrite it.** The dialog
  used to end at an error saying the name exists, with no way forward from there: the only exit was
  to pick a different name. Prumo now asks, states what overwriting destroys — bound repositories,
  documentation, databases, exclusions and policies — and starts the new workspace from scratch when
  confirmed. Cancelling reopens the form with the typed name, so choosing another name is still one
  step. The database passwords the old workspace kept in the IDE password safe are erased with it;
  without that, a datasource later created under the same id would silently inherit a password
  nobody typed.

## [0.1.0-rc.6] - 2026-09-06

### Changed

- Asking for an excluded directory by name now answers with the refusal instead of an empty listing.
  An excluded path inside a wider scan stays invisible, which is the point; but a client that asks
  for the forbidden folder head-on and receives `[]` concludes it is empty and tries another angle.
  A field evaluator reported exactly that. The same applies to a search scoped to an excluded path.

## [0.1.0-rc.5] - 2026-09-06

### Fixed

- **Excluded paths could be reached by changing the case of the name.** The exclusion compared the
  text the client sent, while the Windows filesystem ignores case, so `claude.md` opened the excluded
  `CLAUDE.md` and `DOCS/` listed the excluded `docs/`. A blind agent read an excluded file whole this
  way, minutes after the feature shipped. The comparison now runs over the real on-disk path and
  ignores case on every platform.
- **`prumo_diagnostics` reported a version written by hand in the code**, frozen at `0.1.0` while
  `0.1.0-rc.4` was installed — the one tool whose job is to say what is running. It now reads the
  version from the installed plugin descriptor, and a test fails if any source repeats the version
  declared in the build.

## [0.1.0-rc.4] - 2026-09-06

### Added

- **Excluded paths per repository.** A section in the repository binding dialog lists folders and
  files Prumo refuses to read, list or search. Until now the only way to say "ignore the AI context
  folders" was a sentence in the repository description — which a field test showed for what it is:
  one blind agent read it and complied, another read it and listed `.claude`, `.idea` and `target`
  anyway, reporting "guidance in prose, no enforcement". Now the three repository tools enforce it.
  `.git` became the first built-in entry of that list instead of a special case in the code.
- `prumo_workspace_get_repositories` publishes `excludedPaths`, so the AI client is told the rule
  instead of discovering it by trial.

## [0.1.0-rc.3] - 2026-09-06

Found by three blind agents driving the real MCP transport against a live IDE, without knowledge of
the plugin or the project.

### Fixed

- **Masking could be undone with an alias.** Column masking decided by the output label, so
  `SELECT senha AS num_matricula` returned the secret in clear, and `length(senha)` leaked its size.
  It now decides by the originating column read from the driver metadata, and a query that touches a
  sensitive name also masks its computed columns.
- **The `.git` directory was readable.** Listing and search filtered it; file reading did not, and
  that is where a remote URL with an embedded token lives. All three repository tools now refuse it.
- **A typo was reported as a policy refusal.** An unparseable statement answered "only SELECT … are
  accepted", which reads as a rejected statement type. It now carries the parser's own complaint and
  says the problem is syntax.
- **A Git failure dumped its help screen.** Outside a repository, Git answers with dozens of usage
  lines that reached the MCP client whole. Known cases become one sentence; the rest is capped.

### Removed

- The `ACCESS_EXTERNAL_PATH` policy, which was evaluated, stored and published as granted while no
  tool ever consulted it. Path containment is categorical by design and is not meant to be loosened
  by configuration. Workspaces holding the old field keep opening; the field is ignored.

### Changed

- `docs/security.md` states two limits it did not state: Prumo bounds Prumo's tools, while the IDE's
  MCP server serves other families with wider boundaries — the client's effective reach is their
  union; and masking protects the output, not against inference through a predicate.

## [0.1.0-rc.2] - 2026-09-05

### Added

- Free-text `description` per bound repository, written by the developer and handed to the AI client
  alongside the role, so the assistant knows what each repository is and which of its rules matter.
- Interface labels in Portuguese for workspace type, repository role, access mode, documentation kind
  and document authority. The MCP surface is unchanged: roles and access modes still travel as
  `PRIMARY` and `READ_ONLY`.
- A settings entry in the tool window menu, so the interface language is reachable without hunting
  through the IDE settings tree.

### Changed

- The `TARGET` repository role was consolidated into `PRIMARY`, which now means what is being built,
  including the open project. A role stored in a workspace file that the enum no longer has is
  resolved by a dedicated serializer instead of failing the whole workspace: `TARGET` reads as
  `PRIMARY`, anything else unknown reads as `REFERENCE`.
- Portuguese is now the default language of every document in the repository, with the English
  version beside it as `<NAME>.en.md` and a reciprocal language link under each title. The MCP
  surface stays English-only.

### Fixed

- The workspace editor scrolls instead of clipping: on a screen shorter than the form, the last
  policies were unreachable with no gesture to get to them.

## [0.1.0-rc.1] - 2026-09-05

Release candidate of the MVP. Feature complete and verified by the automated suite; the acceptance
checks that need human eyes — the interface in both languages, the README walkthrough images and a
full cycle with a real AI client — are still open.

### Added

- Workspaces that bind repositories, documentation and PostgreSQL data sources, isolated from one
  another by construction.
- Tool window showing the current workspace, its repositories with role and access mode, and the
  policies — all denied by default.
- MCP surface with 25 tools: workspace context and policy, repository reading and Git state, IDE
  caret context, database introspection and read-only queries, and Prumo Packs.
- Read-only SQL execution with layered protection: read-only credential, read-only session and
  transaction, statement classification over the parsed syntax tree, row ceiling, timeout and a
  sanitized audit trail.
- Automatic masking of columns whose name announces a secret, with no configuration required.
- Prumo Packs: portable team knowledge, saved queries and confined scripts, with risk classification,
  an informed consent screen and an approval queue that only a human can act on.
- Assisted authoring: an AI client can read the pack specification, validate a draft and submit it —
  and cannot install it.
- Interface in English and Brazilian Portuguese, following the language configured in the IDE,
  with an optional override in *Settings · Tools · Prumo MCP* for those who keep the IDE in one
  language and prefer Prumo in another.
- Security suite named and separated (`./gradlew test -PsecurityOnly`), and CI running build, tests
  and plugin verification on Windows and Linux.

### Fixed

- A statement the server rejects — unknown column or table, missing `GROUP BY`, denied privilege —
  now comes back with the server's own message and its hint, instead of a false connection failure.
- The settings page declares its name in the plugin descriptor, so opening *Settings* no longer
  raises `PluginException`.
- The data source form is read from the components on screen, so a filled form is no longer refused
  as empty by *Test Connection* and *Save*.
- The tool window is rebuilt after the workspace is created or edited, so saved policies and
  repositories are shown instead of the state the panel was born with.
- A repository bound to a subdirectory of a Git repository takes the identity of that repository's
  root, so `prumo_workspace_prepare` no longer reports an identity change on an untouched binding.
- Repository role and workspace type explain themselves in the dialog: both describe the work to the
  AI client and enforce nothing, which access modes and policies do.

[Unreleased]: https://github.com/gilmardeveloper/prumo/compare/v0.1.0-rc.10...HEAD
[0.1.0-rc.10]: https://github.com/gilmardeveloper/prumo/compare/v0.1.0-rc.9...v0.1.0-rc.10
[0.1.0-rc.9]: https://github.com/gilmardeveloper/prumo/compare/v0.1.0-rc.8...v0.1.0-rc.9
[0.1.0-rc.8]: https://github.com/gilmardeveloper/prumo/compare/v0.1.0-rc.7...v0.1.0-rc.8
[0.1.0-rc.7]: https://github.com/gilmardeveloper/prumo/compare/v0.1.0-rc.6...v0.1.0-rc.7
[0.1.0-rc.6]: https://github.com/gilmardeveloper/prumo/compare/v0.1.0-rc.5...v0.1.0-rc.6
[0.1.0-rc.5]: https://github.com/gilmardeveloper/prumo/compare/v0.1.0-rc.4...v0.1.0-rc.5
[0.1.0-rc.4]: https://github.com/gilmardeveloper/prumo/compare/v0.1.0-rc.3...v0.1.0-rc.4
[0.1.0-rc.3]: https://github.com/gilmardeveloper/prumo/compare/v0.1.0-rc.2...v0.1.0-rc.3
[0.1.0-rc.2]: https://github.com/gilmardeveloper/prumo/compare/v0.1.0-rc.1...v0.1.0-rc.2
[0.1.0-rc.1]: https://github.com/gilmardeveloper/prumo/releases/tag/v0.1.0-rc.1
