# Design: redact-held-hook-text

## Context

See proposal.md, Why. `PluginComponents.Hook.runs` holds a hook's command with
its arguments, its URL, its MCP tool or its prompt, exactly as declared.
`SnapshotContentService` returns that value, and `describe(Hook)` copies it,
capped at 200 characters, into the finding message.

## Decisions

### D1 — Mask at the presentation boundary, not at parsing

`Hook.runs` stays raw, because `ExecutableSurfaceVetter` scans it for runtime
fetches and follows it to the scripts it launches. Masking happens only where
the text leaves the gateway: building the inventory, and writing the finding
message, which is persisted. As a result the ledger and the stored verdict never
hold the value either.

Alternative: put both reads behind the approver gate. Rejected. The two reads
are the review surface every user browses. The other fields carry no upstream
secret, and the raw bytes are already behind the gated preview.

### D2 — The secret-scan rules are the definition

`SecretScanVetter.redact` applies the vetter's own `RULES` and its
assignment-and-entropy test. "What is masked" and "what the scanner reports"
therefore cannot drift apart. A private key is masked from its header to its
footer, or to the end of the text, because the scanning rule matches only the
header line.

### D3 — Mask before truncating

`describe(Hook)` masks first and cuts to 200 characters afterwards. A token cut
at the boundary would no longer match its shape and would show in part.

## Risks / Trade-offs

- [Text the rules do not recognise still shows] → The same limit as the
  scanner's: a credential with no distinctive shape passes both. The raw
  declaration is still read only through the approver-gated preview.
