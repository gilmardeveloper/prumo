# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/gilmardeveloper/prumo/compare/v0.1.0-rc.5...HEAD
[0.1.0-rc.5]: https://github.com/gilmardeveloper/prumo/compare/v0.1.0-rc.4...v0.1.0-rc.5
[0.1.0-rc.4]: https://github.com/gilmardeveloper/prumo/compare/v0.1.0-rc.3...v0.1.0-rc.4
[0.1.0-rc.3]: https://github.com/gilmardeveloper/prumo/compare/v0.1.0-rc.2...v0.1.0-rc.3
[0.1.0-rc.2]: https://github.com/gilmardeveloper/prumo/compare/v0.1.0-rc.1...v0.1.0-rc.2
[0.1.0-rc.1]: https://github.com/gilmardeveloper/prumo/releases/tag/v0.1.0-rc.1
