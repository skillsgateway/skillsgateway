# Publishing first-party skills

Most marketplaces the gateway governs live somewhere else and are fetched. A
**hosted** marketplace is the other kind: your organisation's own skills, pushed
straight to the gateway, with no forge repository standing in the middle purely
so there is something to pull from.

What does *not* change is everything after the push. A pushed commit is
quarantined, manifest-checked, vetted and held exactly like fetched content, and
is served only once somebody approves it. Content your organisation wrote is
exactly as capable of carrying a planted credential or a prompt injection as
content it did not.

## Step 1 — register the marketplace

A hosted marketplace takes no clone URL, because there is nothing to clone from:

```console
$ curl -X POST localhost:8080/api/marketplaces \
    -H 'Content-Type: application/json' \
    -d '{"name": "platform-skills", "origin": "hosted"}'
```

```json
{"id": 7, "name": "platform-skills", "url": null,
 "origin": "hosted", "pushPolicy": "append-only",
 "publishPath": "/publish/platform-skills"}
```

Supplying a `url` for a hosted marketplace is refused rather than ignored — the
two are mutually exclusive, and a silently unused field is how a marketplace
ends up with an upstream nobody meant to give it. The origin repository is
created here, so you can push the moment this returns.

!!! note "The origin is immutable"

    A marketplace is hosted or upstream at registration and stays that way, for
    the same reason an upstream URL is immutable: changing it would swap the
    supply chain under snapshots that were already approved.

## Step 2 — mint a push-scoped token

Publication authority is **not** fetch authority. It is a separate scope, and it
has no "all marketplaces" form:

```console
$ curl -X POST localhost:8080/api/tokens \
    -H 'Content-Type: application/json' \
    -d '{"name": "platform-skills-ci", "pushScopes": ["platform-skills"]}'
```

| | Fetch scopes | Push scopes |
| --- | --- | --- |
| Omitted means | every marketplace | **none** |
| Wildcard exists | yes, by omission | no |
| Names | any marketplace, or the catalog | hosted marketplaces only |

That asymmetry is deliberate. Every token that existed before publication did
can publish nothing, and no token can be granted publication to everything by
forgetting a field.

The cleartext is returned exactly once, as with any
[access token](../reference/api/tokens.md).

## Step 3 — push

```console
$ git remote add gateway https://token:$TOKEN@gateway.example.com/publish/platform-skills
$ git push gateway main
```

The repository you push must look like any other skill marketplace: a
`.claude-plugin/marketplace.json` whose plugin sources are relative paths inside
the repository. See [Registering a marketplace](registering-a-marketplace.md)
for what the manifest is checked against — a hosted marketplace faces the same
check, and a manifest declaring a non-local source lands `rejected` just as it
would from an upstream.

You can `git clone` the same URL to bootstrap a new machine or a CI checkout;
the same push scope authorizes it. What you are cloning is your own source of
record, not the served marketplace.

### What a push may do

| Rule | Why |
| --- | --- |
| Only `refs/heads/main` | One marketplace, one history — the same single-lineage guarantee the gateway enforces on an upstream's default branch. A second branch or a tag is refused. |
| No ref deletions | Snapshots were taken from that lineage. |
| Fast-forward only, by default | A rewrite changes what a reviewer approved from. |

A rejected push says why:

```
 ! [remote rejected] main -> main (only refs/heads/main may be published; a marketplace has one lineage)
```

### If you really do need to rewrite

Register the marketplace with `"pushPolicy": "allow-rewrite"`. Then a
`git push --force` succeeds — and writes a `marketplace-lineage-rewritten` entry
to the [audit ledger](../concepts/snapshots-and-ledger.md) naming both the old
and the new tip.

Be clear about what you are trading. Approved snapshots keep their content
regardless: they are pinned by SHA, and a rewrite cannot alter bytes that are
already pinned. What a rewrite destroys is the **lineage** — the answer to "what
history was this approved out of". Under `allow-rewrite` that answer lives only
on the ledger.

## Step 4 — approve it

The push ingests itself. Within moments there is a `held` snapshot at the
commit you pushed, with a vetting chain run against it:

```console
$ curl -s localhost:8080/api/marketplaces | jq '.[] | select(.name=="platform-skills") | .snapshots'
```

From here it is the ordinary flow — review the findings, waive what you accept,
approve. See [Approving snapshots](approving-snapshots.md). Until then the
facade does not serve the marketplace at all, exactly as for held upstream
content.

!!! warning "There is no fast path"

    There is deliberately no auto-approval for trusted internal publishers, not
    even for content the organisation wrote itself. Removing the human from the
    gate is a product decision that has been made once already, in the negative
    — see [ADR 0006](../reference/decisions.md) — and it is not something a
    hosting feature gets to decide as a side effect.

## Declaring one in the estate

A hosted marketplace is declarable like any other
([Declarative estate](declarative-estate.md)):

```yaml
skills-gateway:
  estate:
    marketplaces:
      - name: platform-skills
        origin: hosted
        push-policy: append-only
```

No `url`, and no `sync-mode` other than `on-demand`: a hosted marketplace has no
upstream to poll or be notified about, and its ingestion trigger is the push
itself. Asking for a different sync mode is refused.

Push scopes on tokens stay API-only, for the same reason tokens themselves do:
they are user-owned credentials whose secret is shown exactly once.

## What this is not

- **Not a general git host.** One branch, no tags, no pull requests, no issues.
  It is an ingestion point that happens to speak git.
- **Not a way around review.** Same quarantine, same connectors, same approval.
- **Not a mirror target.** Pushing content the gateway could have fetched works,
  but you lose the upstream's provenance for no gain. Register it as an upstream
  marketplace instead.
