# Evidence: connect-client-credential-step

Source state: `7e551d78` (tree `136bd9a7`), on top of `origin/main` `1cf15a45`. Every gate result below is from one fresh run after the
last code edit, on 2026-10-30.

## The commands, run by hand on a clean client

Against a running gateway (the local dev-insecure-auth instance on
`localhost:8080`, serving an approved marketplace `clean-demo`), with a
throwaway token minted for the run and revoked after it. Each run used a fresh
`HOME=$(mktemp -d)`, `GIT_CONFIG_NOSYSTEM=1` and `GIT_TERMINAL_PROMPT=0`; git
2.53.0, bash 5.3.9.

**Before (the wizard's old line) — reproduces #773:**

```
$ printf 'protocol=http\nhost=localhost:8080\nusername=token\npassword=<token>\n' | git credential approve
approve exit=0
$ ls -A $HOME
                                    # nothing stored
$ git clone --depth 1 http://localhost:8080/git/clean-demo
Cloning into 'clean-demo'...
fatal: unable to get password from user
clone exit=128
```

**After (the two lines the wizard now composes, token on stdin):**

```
$ git config --global credential.http://localhost:8080.helper store
$ printf 'Token: ' && read -rs SGW_TOKEN && echo && printf 'protocol=http\nhost=localhost:8080\nusername=token\npassword=%s\n' "$SGW_TOKEN" | git credential approve; unset SGW_TOKEN
Token:
cred exit=0
$ cat ~/.gitconfig
[credential "http://localhost:8080"]
	helper = store
$ stat -c '%a' ~/.git-credentials
600
$ git clone --depth 1 http://localhost:8080/git/clean-demo
Cloning into 'clean-demo'...
clone exit=0
```

**Scoped to this gateway only:** `git config --get-urlmatch credential.helper`
answers `store` for `http://localhost:8080/git/x` and nothing (exit 1) for
`https://github.com/x` and for `http://localhost:9090/x`.

**Pasted into an interactive shell:** an interactive `bash --norc -i` on a
pseudo-terminal, `HISTFILE` in the empty home, was sent both lines as one paste,
then the token as typed input, then the clone and `history -w`:

```
token echoed to terminal: False
token in history: False
clone ok: True
Token prompt shown: True
```

The history file holds the two commands and the clone, with no token.

**What the ledger records:** the newest ledger entry was id 121 (an
`upload-pack` for `clean-demo`) before and after a clone with a wrong token
(`fatal: Authentication failed`, 401) and a clone of an unknown name
(`repository ... not found`, 404): neither refused request wrote an entry.

Not verified by hand: zsh (not installed here). `read -r`/`-s` and the
separate `printf` prompt are the zsh-compatible forms; `read -p` is avoided
because zsh gives it another meaning.

## Spec → tests

| Scenario | Test | Seen failing first |
|---|---|---|
| SVC_GW_AUTH_0068 | e2e `the_wizard_commands_store_the_credential_and_clone_on_a_clean_client` | new e2e; the before run above is the failure it guards |
| SVC_GW_AUTH_0069 | `setup-wizard.test.tsx` `the_credential_command_names_the_store_helper_for_this_origin_only_and_reads_the_token_at_a_prompt`, `a_minted_token_is_shown_in_its_own_field_and_in_no_command` | yes |
| SVC_GW_AUTH_0070 | `the_token_step_says_the_token_is_not_limited_to_this_marketplace`, `the_ledger_statement_covers_served_fetches_and_not_refused_requests` | yes |
| SVC_GW_AUTH_0014 (revised) | e2e `setup_wizard_composes_origin_derived_commands_and_holds_show_once` reads the token from its field instead of parsing it from the command; same show-once and no-redisplay assertions | n/a |

## Gates

| Gate | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, UI gate, jar | `./mvnw clean verify` | `Tests run: 1175, Failures: 0, Errors: 0, Skipped: 9` · UI `Test Files 31 passed (31)` · `Tests 238 passed (238)` · `BUILD SUCCESS` · `Total time: 17:23 min` (shared machine, two other suites running) |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 112 passed (112)` |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `28 passed (2.1m)`, including `the_wizard_commands_store_the_credential_and_clone_on_a_clean_client` and `setup_wizard_composes_origin_derived_commands_and_holds_show_once` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | exit 0 · `REQUIREMENTS: 369` · 1198 passed, 0 failed, 0 skipped · 0 SVCs missing tests · GW_AUTH_0062 3/3, GW_AUTH_0063 2/2 |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 2.18 seconds` |
| Supply chain | no dependency added or changed | — |

## Known limits

- The marketplace-add command the wizard composes,
  `claude plugin marketplace add {origin}/git/{name}`, is outside this change.
  Run by hand on the same clean client after the credential step, Claude Code
  2.1.314 treated the URL as a marketplace file to download and answered
  `HTTP 401`; with a `.git` suffix (which the facade accepts) it cloned through
  git, used the stored credential and added the marketplace.
