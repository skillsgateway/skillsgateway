# Proposal: the client wizard's credential step works on a clean machine

## Why

The "Connect a client" wizard's first command is
`printf 'protocol=…\nhost=…\nusername=token\npassword=<PAT>\n' | git credential approve`.
`git credential approve` hands the credential to the configured helpers, and
with none configured it exits 0 and stores nothing. The wizard's clone command
then fails with `fatal: unable to get password from user` (#773). The same line
puts the token in the shell history, the dialog implies a token named
`<marketplace>-client` is limited to that marketplace when it is not, and its
closing line says every fetch is recorded on the ledger when a refused request
(401, or 404 for a held or unknown name) is not.

## What Changes

- New `GW_AUTH_0062 — The client wizard's credential step works on a client
  with no credential helper and keeps the token off the command line`. The step
  first names git's `store` helper for the gateway's own origin only
  (`git config --global credential.<origin>.helper store`), then reads the token
  at a prompt that does not echo it and passes it to `git credential approve`.
  The token is no longer substituted into any command; once minted it is shown
  in its own show-once field to paste at the prompt. The step says the `store`
  helper keeps the token in plaintext and that an OS keychain helper is the
  alternative.
- The marketplace-add command becomes
  `claude plugin marketplace add {origin}/git/{name}.git`. Without the suffix
  Claude Code downloads the URL as a marketplace file over plain HTTP, with no
  credential, and is answered `401`; with it, Claude Code clones it as a git
  remote through the stored credential. The facade already serves both forms.
  `GW_AUTH_0014` says the command addresses the facade clone path as a git
  remote.
- New `GW_AUTH_0063 — The client wizard states what a token reaches and what
  the ledger records`. The token step says the token is not limited to the
  marketplace the wizard was opened from; the closing line says served fetches
  are recorded, and that refused requests are not.
- Modified `GW_AUTH_0014 — Client setup wizard`: the minted cleartext is shown
  in the wizard only while it is open, rather than substituted into the
  command snippets, and the marketplace-add command addresses the facade clone
  path as a git remote. The show-once and never-redisplay obligations are
  unchanged.
- New `SVC_GW_AUTH_0068` (e2e: the wizard's own commands, run in a shell with
  an empty `HOME` and no prompt, store the credential and clone the approved
  marketplace), `SVC_GW_AUTH_0069` (unit: command composition and the token
  field), `SVC_GW_AUTH_0070` (unit: the reach and ledger statements).
- Docs: `guides/consuming-skills.md`, `reference/portal.md`,
  `guides/virtual-catalog.md`, `guides/client-enforcement.md`, and every page
  that claims every fetch is recorded.
- Not **BREAKING**: a portal copy and command change; no API or configuration
  changes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `admin-portal`: modifies `GW_AUTH_0014`, adds `GW_AUTH_0062` and
  `GW_AUTH_0063`.

## Impact

- `src/main/frontend/src/components/setup-wizard.tsx`, its unit tests and
  stories; the e2e spec for `SVC_GW_AUTH_0014` reads the minted token from its
  new field.
- No server change. Recording refused requests on the ledger is out of scope.

The capability map absorbs this change: it adds no package, object type, role,
sweep or configuration leaf.
