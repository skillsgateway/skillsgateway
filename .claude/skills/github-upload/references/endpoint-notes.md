# GitHub user-attachments upload endpoint — behavior notes

Verified 2026-08-12 against github.com. Read this when an upload misbehaves or
when someone questions whether the approach is sound.

## The endpoint

```
POST https://uploads.github.com/user-attachments/assets
     ?name=<filename>&content_type=<mime>&repository_id=<numeric repo id>

Authorization: Bearer <token>       # an ordinary gh OAuth token works
Content-Type:  <mime>               # omit this and it 400s before checking anything else
Accept:        application/json

<raw file bytes as the request body>
```

Returns `201 {"url": "https://github.com/user-attachments/assets/<uuid>"}`.

That URL is a real GitHub attachment — byte-identical in behavior to one
produced by dragging a file into the comment box. Reference it as normal
markdown (`![alt](url)`) in a body applied with `gh pr edit --body-file` or
`gh issue comment --body-file`. Nothing is committed to the repository.

Why this endpoint and not the obvious one: the web UI posts to
`https://github.com/upload/policies/assets`, which is CSRF-protected and
authenticates with a browser `user_session` cookie. A PAT or `gh` token gets an
HTTP 422 HTML error page there. That cookie is equivalent to the account
password and unscoped — tools that scrape it out of a browser profile are a
poor trade for pictures. The `uploads.github.com` endpoint accepts a bearer
token instead.

## Two behaviors that look like bugs and are not

1. **`curl`ing the returned URL gives 404 (anonymously).** It is a handle, not
   a CDN path. GitHub rewrites it at render time into a short-lived signed
   `private-user-images.githubusercontent.com?jwt=…` URL (~5 min expiry), and
   it mints one for logged-out visitors too — verified on a **public** PR page
   fetched without credentials. With a bearer token the bare URL returns 200,
   which is a useful check that an upload landed.

2. **The asset is orphaned until referenced.** It only becomes reachable once a
   comment or body citing it has been saved. Upload first, then edit the body;
   don't expect the URL to be live in between.

## Constraints and risks

- **Images and video only.** Other file types are rejected on this endpoint.
- **Push access required** to the repository whose `repository_id` is passed.
- **Undocumented.** It can change or vanish without notice. Don't build
  something load-bearing on it without a fallback, and expect to re-verify.
- **Numeric repo id.** `gh repo view --json id` returns the *GraphQL node id*,
  which will not work. The script uses the REST id from
  `GET https://api.github.com/repos/OWNER/NAME` (`.id`).
- **Untested elsewhere — flag these before assuming they work:**
  - a fine-grained PAT rather than a `gh auth login` OAuth token;
  - an org enforcing SAML SSO — the token will likely need explicit
    authorization for that org;
  - GitHub Enterprise Server — `uploads.github.com` is github.com-specific.
- **Private-repo access control: verified on one tenant.** The signed-URL
  rewrite is per-viewer, so access follows repo permissions. Verified
  2026-08-12 against an asset referenced in a private repo under a GitHub
  enterprise: an anonymous fetch of the bare asset URL is 302-redirected
  to the enterprise SSO login with no image bytes served; an
  **authenticated GitHub user outside the enterprise** (personal account,
  bearer token) gets the same SSO redirect and no bytes; a repo member with a
  bearer token gets 200. Caveat: this was tested on an enterprise (EMU) tenant
  where the SSO wall does the gating — behavior for private repos on plain
  github.com is still an assumption. Either way, deny-by-default held; but
  still rely on the sensitivity rule below rather than on repo privacy.

## Permanence — assume every upload is forever

Deleting or editing the referencing comment or body does **not** delete the
asset; the URL keeps resolving. There is no documented deletion mechanism for
user-attachments — no API, no UI. The bytes live in GitHub-owned storage with
no retention policy, deletion path, or audit trail you control, so GDPR
erasure and internal retention rules cannot be honored for that copy.

Consequence: assume every upload is **permanent and unrecoverable**. Never
upload anything that must not exist forever outside storage you control —
in particular sensitive content (customer data, credentials, internal
system details visible in screenshots). This is why SKILL.md makes the human
checkpoint mandatory.

## What to avoid, and why

- **Don't commit images to a branch** and link `raw.githubusercontent.com`.
  It works, but it puts binaries in the repo permanently, and the URLs break
  entirely if the repo is private (raw needs a token; markdown can't send one).
- **Don't host images as release assets.** The most commonly suggested
  workaround, and dangerous for any project whose tooling enumerates releases —
  an auto-updater, a version picker, an install script. A throwaway tag like
  `pr-42-images` can be picked up as a real version and offered to users.

## Credit

The endpoint is the one used by [`gh-image`](https://github.com/drogers0/gh-image)
(MIT), whose `internal/upload/bearer.go` documents its constraints. That
project is a `gh` extension covering both this token path and a browser-cookie
path for what the token cannot do (non-image files, repos without push access).
Its cookie fallback wants the `user_session` cookie, which its own SECURITY.md
describes as "equivalent to your GitHub password" — for screenshots, the token
path avoids that entirely.
