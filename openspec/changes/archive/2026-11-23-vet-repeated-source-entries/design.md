# Design: vet every marketplace entry that repeats a plugin source

## Context

`PluginComponents.read(files, root, manifest, pointer)` returns what one
plugin provides: the root's own files (default hook file, `plugin.json`,
`.mcp.json`, `.lsp.json`, `commands/`, `agents/`, `skills/`) merged with what
the entry at `pointer` declares. Claude Code installs each marketplace entry as
its own plugin, so the surface of a snapshot is the union over every
(root, entry) pair. `ExecutableSurfaceVetter` built `Map<root, firstPointer>` with `putIfAbsent`,
so the union lost every later entry over a root already seen.

## Decisions

### Which entries to read

`PluginComponents.entriesByRoot(manifest, limit)` returns, per root, every
entry that declares any of `commands`, `agents`, `hooks`, `mcpServers`,
`lspServers`, `skills` (the keys `read` consults on an entry), plus the first
entry over the root that declares none. Entries that declare none all read the
same thing, the root's defaults, so one stands for all. That one is read even
when a declaring entry came first: without a `plugin.json`, an entry's
`commands` or `agents` paths replace the default scan for that entry only, so
the plain entry is the only one that reads the default directory.

The first version kept only the first entry over a root plus later declaring
ones. CodeRabbit found the hole on review: a first entry declaring `agents`
suppressed the default `agents/` scan, and the plain entry that would have read
it was dropped. A test reproduced it before the fix.

Rejected: reading every entry. Correct, but a manifest repeating one root a
thousand times would read it a thousand times for nothing.

### Following launched files per root

A record two roots reach (a hook, a module, a server) is examined once per
root, and so is the chain of files it launches: `follow` keys its traversal by
kind, root and path, because a shared script's `${CLAUDE_PLUGIN_ROOT}/…`
names a different file under each root. A file already scanned, under another
root or as a module's, is traversed again without a second scan, so its
findings and the launched-file count stay as they were. CodeRabbit found the
path-only key on its second review; two tests reproduced it before the fix.

### The surface comparison stays as it is

`SurfaceComparison` builds its roots the same first-entry way, but it cannot be
fooled by it: it treats the marketplace manifest as not inert as soon as any
entry declares more than descriptive fields, so a snapshot carrying an inline
declaration is never "unchanged" and never approved automatically. A test
written for the gap there failed for that reason (`not inert:
.claude-plugin/marketplace.json (declares more than descriptive fields)`), not
for the missing declaration, so a change there would be unobservable. It is
left alone rather than changed without a test that can tell.

Rejected: reading every entry. Correct, but a manifest repeating one root a
thousand times would read it a thousand times for nothing.

### Bounding the work

`GW_VETTING_0064 — The executable-surface vetter's work is bounded in plugin
roots and stops when cancelled` stays as is: at most 1,000 roots. Entries are
bounded by `ManifestPolicy.MAX_PLUGINS` (1,000), which ingest already enforces
and the surface comparison already fails past. The vetter reads only the first
1,000 entries; a snapshot past that, which only one ingested before
`GW_INGEST_0074 — A manifest's plugins are bounded in number and list each
source once` can be, gets the existing high `too-many-plugin-roots` finding,
so it fails closed rather than passing on a partial read.

Within that bound, reading one root under many declaring entries repeats the
root's own JSON files and skill frontmatter. `PluginComponents.Cache` (#938)
already memoizes frontmatter; it now also memoizes each JSON declaration file
with the problem reading it, so each file is parsed once per tree. The
per-entry cost left is the entry's own declarations and the path scans for
default directories.

### Counting and duplicates

The same hook, module or server can now arrive from two reads of one root.
Findings already de-duplicate on id, location and message. The vetter's
coverage counters (`N hook(s)`, …) would not, so the vetter skips a component
it has already processed under the same root: the root with the hook, module
or server record, which holds its location and what it runs. The key includes
the root because a hook two roots reach (a `skills` path from one root into
another) launches `${CLAUDE_PLUGIN_ROOT}` files under each, and each must be
followed. Keying by the record alone skipped the second root's scripts; also
found by CodeRabbit and reproduced by a test first.

Hook-module graphs join frontmatter and JSON in `PluginComponents.Cache`,
keyed by root, module path and location (all three shape the result), so a
root read under many declaring entries scans each module graph once.

## Risks

- A snapshot that relied on the gap now warns or fails. That is the fix.
- An entry that declares a key this list misses would still be skipped. The
  list is the set of keys `PluginComponents.read` reads from an entry; a test
  pins each key.
