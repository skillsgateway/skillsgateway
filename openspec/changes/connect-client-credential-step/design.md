# Design

## Context

See proposal.md, "Why". The wizard derives every URL from the browsing origin
(`GW_AUTH_0014 — Client setup wizard`), and its credential line is the primary
copy target (`GW_AUTH_0043 — The client wizard is offered from every
marketplace section and states the held-snapshot outcome`). The facade is
authenticated by a PAT over HTTP Basic; git keeps one credential per
protocol, host and user name unless `credential.useHttpPath` is set.

## Goals / Non-Goals

**Goals:** the wizard's commands, pasted into bash or zsh on a machine with no
git configuration, leave a working clone; the token appears in no command text
and no shell history.

**Non-Goals:** Windows `cmd`/PowerShell variants (the existing snippet was
already POSIX); scoping the wizard's token to one marketplace; recording refused
facade requests on the ledger; the marketplace-add command's URL form.

## Decisions

**Name the `store` helper for the gateway's origin only.**
`git config --global credential.<origin>.helper store` matches by URL, so it
applies to this gateway (scheme, host and port) and to nothing else on the
machine. Alternatives: a global `credential.helper store` changes how every
other remote's credentials are kept, which a portal has no business deciding;
"say a helper is required" leaves the reader to work out which helper and how.
`store` is the one helper every git build ships that persists; `cache` expires
and the keychain helpers differ per OS. A reader who already has a keychain
helper skips the first line, and `approve` then stores the token there, since
it goes to every configured helper.

**Read the token at a prompt.** The second line is one shell line —
`printf 'Token: ' && read -rs SGW_TOKEN && echo && printf '…password=%s\n'
"$SGW_TOKEN" | git credential approve; unset SGW_TOKEN` — so pasting both lines
runs the `read` last, after the paste has drained, and the token is typed or
pasted into a prompt that does not echo. `read -s` is in bash and zsh; zsh's
`read -p` means something else, hence the separate `printf` prompt. Rejected:
a leading space or `HISTCONTROL` hint, which depends on shell settings zsh does
not enable by default; `stty -echo`, which leaves the terminal silent if
interrupted.

**The token moves out of the commands into its own field.** Once minted, the
cleartext is shown in a show-once field with its own copy control, under the
same rule as before: only while the wizard is open. The commands are identical
before and after minting, so the `<YOUR_TOKEN>` placeholder goes away. This is
the one change to `GW_AUTH_0014`'s wording; its verification reads the token
from the field instead of parsing it out of the command, with the same
assertions.

**The token's reach is stated, not narrowed.** A token minted here is
unscoped. Scoping it to the marketplace would break the reader's second
marketplace: git keeps one credential per gateway, so the second wizard run
would replace the first token. The token step says so.

## Risks / Trade-offs

- [`store` keeps the token in plaintext in `~/.git-credentials`] → stated in
  the step with the keychain alternative; git creates the file with mode 0600.
- [A non-bash/zsh shell (dash, fish) lacks `read -s`] → the step says bash or
  zsh; the facade itself is unchanged and the reference docs give the plain
  form.
