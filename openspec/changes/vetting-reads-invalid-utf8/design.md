# Design: vetting-reads-invalid-utf8

## Context

See proposal.md, Why. `ContentRules.text` and `HookModules.text` decoded with
`CodingErrorAction.REPORT` and returned `null` on any malformed sequence. Every
caller reads `null` as "binary".

## Decisions

### D1 — NUL is the binary test

A NUL byte is what git, `file` and editors use to call content binary, and no
shell script, JavaScript module or Markdown file legitimately holds one.

Alternative: JGit's `RawText.isBinary`. Rejected, because it also calls content
with a lone CR binary. That would move the bypass to a different byte and stop
scanning files that are scanned today.

### D2 — Replacement characters, not a second strict pass

`new String(bytes, UTF_8)` replaces each malformed sequence with U+FFFD. The
pattern rules match the ASCII command text around the stray byte, so a
download-and-execute or an injection phrase on another line is found exactly as
before.

### D3 — Severities unchanged

`GW_VETTING_0049 — Hook declarations and launched files the vetter cannot read
are reported` deliberately makes an unreadable hook target a medium finding.
Whether a file in an executable position that cannot be read should block is a
separate decision, taken with the other fail-closed vetting gaps. This change
only stops misclassifying readable files.

## Risks / Trade-offs

- [Snapshots gain findings on re-vetting] → That is the point: those files were
  never scanned. Existing waivers still apply by fingerprint.
