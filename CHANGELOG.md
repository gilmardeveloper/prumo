# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/gilmardeveloper/prumo/compare/v0.1.0-rc.1...HEAD
[0.1.0-rc.1]: https://github.com/gilmardeveloper/prumo/releases/tag/v0.1.0-rc.1
