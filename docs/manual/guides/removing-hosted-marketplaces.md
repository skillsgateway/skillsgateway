# Removing hosted marketplaces

The gateway no longer hosts marketplaces. An organisation that writes its own skills keeps
them on a forge — GitHub, Gitea, GitLab — and registers that repository as an
[upstream](registering-a-marketplace.md), with an
[upstream credential](private-upstreams.md) if it is private. This page is for an operator
whose gateway **held hosted marketplaces** (registered with `origin: "hosted"`, pushed to on
`/publish/{name}`) and is upgrading past the release that removed them. If you never
registered one, nothing here applies.

!!! danger "Upgrading deletes the gateway's record of every hosted marketplace"

    The schema migration that removes hosting (`V3__remove_hosted_marketplaces`) **purges
    every hosted marketplace, live or already removed, with everything that depends on it**:
    its snapshots, approvals, vetting runs and findings, waivers, exemptions, role grants and
    settings, and the audit-ledger entries recorded under its marketplace id. The project is
    in beta, and this was decided in favour of a clean schema.

    - **Those marketplaces stop being served at the moment of the upgrade.** A clone of
      `/git/{name}` answers as it does for any name the gateway does not know.
    - **Their history is gone.** If the audit ledger is evidence you must keep, export it
      *before* upgrading ([Exporting the audit ledger](exporting-the-audit-ledger.md));
      entries already exported to a SIEM stay there.
    - **The repositories are not touched.** Each hosted marketplace's bare repository, at
      `{data-dir}/hosted/{name}.git` or under the object store's `hosted/` prefix, stays
      exactly where it is. It is the **only copy** of that publisher's source, and it is not
      in the database. Do not delete it until you have copied it.
    - **There is no way back** except restoring the database and storage pair you backed up
      ([Backing up, restoring and upgrading](backup-and-upgrade.md)).

    Names that a *later* upstream marketplace reused keep all of that marketplace's own
    history: the purge is by marketplace id, never by name alone.

## Before the upgrade (optional)

You can prepare, and one preparation avoids an outage.

1. **Export the ledger** if you need it, as above.
2. **Export each repository.** On the old version, clone it with a token that carries a push
   scope for it. This works on both storage backends:

    ```console
    $ git clone https://x:$PUSH_TOKEN@gateway.example.com/publish/platform-skills platform-skills
    ```

    On the filesystem backend you can instead clone the bare repository from disk:
    `git clone {data-dir}/hosted/platform-skills.git`. **On the object-store backend there is
    no path to clone from**, and the new version cannot open those repositories at all, so
    clone through `/publish` *before* upgrading.
3. **Avoid the gap.** After the upgrade a hosted marketplace serves nothing until its forge
   replacement has an approved snapshot. To have none, push the clone to a forge now,
   register that repository on the **old or new** version under a *temporary* name, approve
   its first snapshot, and re-point consumers at the temporary name before upgrading. Or
   accept the gap and follow the next section.

## After the upgrade

The portal's **Overview** page shows administrators a notice while repositories remain: *This
gateway no longer hosts marketplaces. N repositories from the former hosting feature are
still in its data store; copy each one (`git clone <location>`) and then delete the folder.*
It lists each repository with its name, its location (a path, or an object-store key prefix),
and, on the filesystem backend, its last commit. The same list is `GET
/api/v1/storage/former-hosted-repositories` (administrators only). It carries no delete
button: you copy and delete the folders yourself. It is read from storage each time, so it
disappears the moment the last folder does.

For each repository listed:

1. **Copy it.**

    ```console
    $ git clone /var/lib/skills-gateway/hosted/platform-skills.git platform-skills
    ```

2. **Push it to a forge.** Create the repository there, then:

    ```console
    $ cd platform-skills
    $ git push --mirror https://git.example.com/acme/platform-skills.git
    ```

    The gateway ingests a repository's default branch, which for a clone of a hosted
    repository is `main`.
3. **Register the forge repository as an upstream under the old name.** The name is free: the
   purge removed the marketplace that held it.

    ```console
    $ curl -X POST localhost:8080/api/v1/marketplaces \
        -H 'Content-Type: application/json' \
        -d '{"name": "platform-skills", "url": "https://git.example.com/acme/platform-skills.git"}'
    ```

    A private repository needs an [upstream credential](private-upstreams.md) first.
4. **Ingest it and approve its first snapshot.** It is a new marketplace with a history of its
   own: the old snapshots, approvals and ledger entries are gone and are not carried over, so
   the first snapshot is held like any other and needs its own approval
   ([Approving and rejecting snapshots](approving-snapshots.md)).
5. **Delete the folder** (`{data-dir}/hosted/platform-skills.git`, or the object-store
   prefix) once you have verified the forge copy. The notice shrinks and goes.

!!! warning "Consumers see an outage between the upgrade and step 4"

    Until the replacement's first snapshot is approved, `/git/platform-skills` answers 404,
    and `marketplace add` and `git clone` fail. Only the preparation above avoids it.

## What consumers keep

- **Remotes and URLs.** A clone or `marketplace add` URL under `/git/{name}` keeps the same
  shape; once the replacement is approved the same URL serves it.
- **Fetch tokens.** Every token keeps the fetch scopes it had.
- **Push-only tokens become ordinary fetch tokens.** The push scope is dropped with its
  column, and a token without fetch scopes fetches **every** marketplace (that is what an
  unscoped token means). If you issued a token only to publish, it is now an unscoped fetch
  token: **revoke it** ([the token API](../reference/api/tokens.md)). The gateway has no
  endpoint that accepts a push any more.

## An estate file that still declares `origin: hosted`

A [declarative estate](declarative-estate.md) entry that sets `origin: hosted`, or any
`push-policy`, fails with a message naming the removal:

```text
hosted marketplaces were removed: the origin 'hosted' is no longer supported. Keep the skills
on a forge and register that repository as an upstream (see the guide removing-hosted-marketplaces)
```

Delete `origin` and `push-policy` from the entry and give it the forge repository's `url`.
`origin: upstream` is still accepted, and means what every marketplace now is.

## What is left on disk, and when you may delete it

SQL cannot touch a directory, so the migration leaves every hosted repository where it was:
`{data-dir}/hosted/*.git` on the filesystem backend, the `hosted/` key prefix of the gateway's
own prefix on the object-store backend. They are not read by anything else in the gateway, and
**you may delete them as soon as you have a copy you trust**. Until then keep them in your
backups. On the object-store backend the notice lists names and the key prefix only; copy
what you need with the storage tooling you provisioned the bucket with, or clone through
`/publish` on the old version before upgrading, as above.

!!! note "The notice is temporary"

    The route, the banner and this paragraph are removed under one condition:
    **REMOVE-AFTER: two minor releases after the release that ships migration
    V3__remove_hosted_marketplaces.** After that the gateway does not tell you which folders
    remain, and the directories are still yours to delete.

## Why

[ADR 0024 — The gateway does not host marketplaces](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0024-the-gateway-does-not-host-marketplaces.md)
records the reasoning, and
[the compatibility page](../reference/compatibility.md#the-api-contract) records what this
change took out of the API.
