# Security policy

**English** · [Português (Brasil)](SECURITY.md)

## Supported versions

Prumo is pre-1.0. Security fixes land on `main` and in the next release; older builds are not
patched.

## Reporting a vulnerability

**Do not open a public issue.** Send the report to **gilmarsilva.developer@gmail.com** with:

- what the vulnerability allows an attacker (or an AI client) to do;
- the steps to reproduce it, including the workspace configuration involved;
- the plugin version, the IDE version and the operating system.

You will get an acknowledgement within **five working days** and a decision — fix, mitigation or
"working as designed, with this documented limit" — within **thirty days**. If the report leads to a
fix, you will be credited in the changelog unless you ask otherwise.

## What counts as a vulnerability here

The guarantees Prumo makes are listed in [docs/security.md](docs/security.md). A report is in scope
when it breaks one of them, for example:

- reaching the content, configuration, credentials or audit trail of another workspace;
- escaping a repository root through a path, symlink, junction or mount point;
- writing through a `READ_ONLY` repository or data source;
- extracting a credential from any artifact Prumo persists, logs or returns;
- installing or activating a pack without the consent screen;
- making Prumo write inside a user's repository.

## What is a documented limit, not a vulnerability

These are stated in the security document and are not accepted as reports:

- an obfuscated pack script passing the risk classifier — static analysis is signal detection, not
  proof;
- a pack script reaching the filesystem through an absolute path — the confinement is the working
  directory and the environment, not an OS sandbox;
- a permissive database credential allowing more than intended — the read-only credential is the
  user's responsibility, and Prumo's layers reduce blast radius rather than replace it.
