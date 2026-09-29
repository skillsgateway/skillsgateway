---
name: github-upload
description: Attaches images and video to GitHub PRs and issues from the command line, so screenshots render in PR/issue bodies without a human dragging files into the browser. Use when asked to add a screenshot to a PR, attach an image to an issue, embed before/after images in a PR description, or upload media to GitHub from a script or agent.
metadata:
  source: "jimisola/claude-plugins, plugin github-tools 1.4.0, skill upload (commit 3916f7d); vendored so the repository does not depend on a personal plugin"
---

Requires Node 22.18+. Works the same on Windows, macOS, and Linux; no dependencies to install. `gh` and `git` are used when present but are not required.

## Workflow

1. **Human checkpoint — mandatory.** View the file (Read it) and show/describe it to the user, then get their explicit confirmation before uploading. This skill runs agent-triggered, so this is the only human eyeball in the loop — the thing drag-and-drop gave for free. Never upload without it.

   **Offer to open it in their viewer as part of asking.** Reading a file renders it for *you*, not necessarily for the user — a terminal may show nothing, and describing an image is not the same as them seeing it. Offer the open, and if they take it run the platform's opener in the background (`xdg-open` on Linux, `open` on macOS, `start` on Windows). Video especially: describing a clip is no substitute for watching it.

   As part of that check, confirm the content is safe to publish: **never upload anything containing sensitive data** — customer data, credentials or tokens, or internal system details visible in screenshots. Uploads land in GitHub-owned storage with no deletion mechanism; treat every upload as permanent and unrecoverable.

2. Upload the file(s) — prints one markdown image line per file:

   ```
   node .claude/skills/github-upload/scripts/attach.ts shot.png other.webm
   ```

   Outside a checkout of the target repo, add `--repo owner/name`.

3. Put those `![…](https://github.com/user-attachments/assets/…)` lines into the PR/issue body or a comment and save it — e.g. `gh pr edit --body-file`, `gh issue comment --body-file`, or the REST API. The attachment only becomes live once a saved body references it.

## Auth and repo resolution (what the script does)

- **Token**: `gh auth token` if the GitHub CLI is installed and logged in; otherwise `GITHUB_TOKEN` / `GH_TOKEN`. If neither is available it stops and tells the user to log in with `gh auth login` or set an env var — never invent a token. The token needs **push access** to the target repository.
- **Repository**: `--repo` flag, else `GITHUB_REPOSITORY` (set in GitHub Actions), else the `origin` git remote.

## Constraints — read before promising anything

- **Images and video only** (png/jpg/jpeg/gif/webp/mp4/webm). The endpoint rejects other types. The script also verifies the file's magic bytes match its extension — a renamed non-media file (`mv secrets.env secrets.png`) is refused. (The original plugin adds a Claude Code hook that repeats the check and logs every upload; it is not vendored, so the script's own check is the one that applies here.) SVG is deliberately unsupported: no magic number to verify, and it's scriptable.
- **Uploads are permanent.** Deleting or editing the referencing comment does not delete the asset and there is no documented deletion mechanism — hence the human checkpoint and sensitive-data rule above.
- **github.com only.** The endpoint does not exist on GitHub Enterprise Server; the script refuses non-github.com remotes rather than failing confusingly.
- **The endpoint is undocumented** and can change without notice. Fine for screenshots in PRs; don't build load-bearing automation on it without a fallback.
- A bare `curl` of the returned URL gives **404 anonymously — that is normal**, not a failed upload. GitHub rewrites it into a short-lived signed image URL at render time, for logged-out viewers too. With the bearer token the URL returns 200, which is how to verify an upload landed.
- Untested edge cases to flag to the user rather than assume: fine-grained PATs, and orgs enforcing SAML SSO (the token may need explicit org authorization).

Do **not** work around a failure by committing images to a branch (permanent binaries; raw URLs break on private repos) or by hosting them as release assets (throwaway tags can be picked up by auto-updaters and version pickers as real releases).

Full endpoint behavior, verification notes, and the anti-pattern rationale: [references/endpoint-notes.md](references/endpoint-notes.md).
