import { GitCompareArrows, Link2, ShieldAlert } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { toast } from "sonner";
import { formatJson } from "@/lib/json-format";
import { ApiError } from "@/api/client";
import {
  useSnapshotDiff,
  useSnapshotFile,
  useSnapshotFiles,
  type SnapshotDiffEntry,
} from "@/api/queries";
import { MarkdownView } from "@/components/markdown-view";
import { SegmentedGroup } from "@/components/segmented-group";
import { SnapshotFileTree } from "@/components/snapshot-file-tree";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  ancestorDirectories,
  buildSnapshotTree,
  filterSnapshotTree,
  type ChangeStatus,
  type TreeNode,
} from "@/lib/snapshot-tree";

/** Every directory in the tree — what "expand all" means while a filter is narrowing it. */
function allDirectories(nodes: readonly TreeNode[]): string[] {
  return nodes.flatMap((node) =>
    node.kind === "directory" ? [node.path, ...allDirectories(node.children)] : [],
  );
}

function Notice({ children }: { children: React.ReactNode }) {
  return <p className="text-sm text-muted-foreground">{children}</p>;
}

/** A refusal is a state of the explorer, not a blank pane: the role rule is the server's. */
export function Forbidden() {
  return (
    <div role="alert" className="flex items-start gap-3 rounded-md border p-4">
      <ShieldAlert className="mt-0.5 size-4 shrink-0 text-destructive" aria-hidden />
      <div className="space-y-1">
        <p className="text-sm font-medium">You cannot read this snapshot's contents.</p>
        <p className="text-sm text-muted-foreground">
          Reading held content needs the administrator role or approver of this snapshot's
          marketplace.
        </p>
      </div>
    </div>
  );
}

/** Unified diff text, coloured by line kind with theme tokens. */
function DiffText({ diff }: { diff: string }) {
  return (
    <pre className="overflow-x-auto rounded-md border bg-muted p-3 font-mono text-xs">
      {diff.split("\n").map((line, i) => {
        const kind =
          line.startsWith("+") && !line.startsWith("+++")
            ? "text-primary"
            : line.startsWith("-") && !line.startsWith("---")
              ? "text-destructive"
              : line.startsWith("@@")
                ? "text-muted-foreground"
                : "";
        return (
          <div key={i} className={kind}>
            {line}
          </div>
        );
      })}
    </pre>
  );
}

/** The blob itself: inert text, Markdown rendered without any HTML pipeline, bounds stated. */
function FileContent({ snapshotId, path }: { snapshotId: number; path: string }) {
  const file = useSnapshotFile(snapshotId, path);
  if (file.isLoading) return <Notice>Loading {path}…</Notice>;
  if (file.isError) {
    if (file.error instanceof ApiError && file.error.status === 403) return <Forbidden />;
    return (
      <p role="alert" className="text-sm text-destructive">
        {file.error.message}
      </p>
    );
  }
  const content = file.data;
  if (!content) return null;
  if (content.binary) {
    return <Notice>Binary file ({content.size} bytes) — content is not rendered.</Notice>;
  }
  return (
    <div className="space-y-2">
      {content.truncated ? (
        <p className="text-xs text-muted-foreground">
          Truncated: showing the first part of {content.size} bytes.
        </p>
      ) : null}
      {path.endsWith(".md") ? (
        <MarkdownView text={content.text ?? ""} />
      ) : path.endsWith(".json") ? (
        <JsonContent text={content.text ?? ""} truncated={content.truncated === true} />
      ) : (
        <pre className="overflow-x-auto rounded-md border bg-muted p-3 font-mono text-xs">
          {content.text ?? ""}
        </pre>
      )}
    </div>
  );
}

const JSON_VIEWS = [
  { value: "formatted", label: "Formatted" },
  { value: "raw", label: "Raw" },
] as const;

/**
 * JSON re-indented token for token, with the stored bytes one control away. A truncated blob or
 * a document that does not tokenise is shown as stored — formatting half a document, or guessing
 * at a broken one, would show the reviewer something the snapshot does not contain.
 */
function JsonContent({ text, truncated }: { text: string; truncated: boolean }) {
  const [view, setView] = useState<"formatted" | "raw">("formatted");
  const formatted = truncated ? null : formatJson(text);
  const raw = (
    <pre className="overflow-x-auto rounded-md border bg-muted p-3 font-mono text-xs">{text}</pre>
  );
  if (formatted === null)
    return (
      <div className="space-y-2">
        {truncated ? null : (
          <p className="text-xs text-muted-foreground">Not valid JSON — shown as stored.</p>
        )}
        {raw}
      </div>
    );
  return (
    <div className="space-y-2">
      <SegmentedGroup label="JSON view" hideLabel value={view} options={JSON_VIEWS} onChange={setView} />
      {view === "raw" ? (
        raw
      ) : (
        <pre className="overflow-x-auto rounded-md border bg-muted p-3 font-mono text-xs">{formatted}</pre>
      )}
    </div>
  );
}

/** This one file against what the marketplace serves, read out of the snapshot's own diff. */
function FileDiff({
  entry,
  hasBaseline,
  unavailable,
  pending,
}: {
  entry: SnapshotDiffEntry | undefined;
  hasBaseline: boolean;
  /** The comparison could not be read at all — saying "unchanged" here would be a lie. */
  unavailable: boolean;
  /** Still in flight. Every other answer below would be a statement we cannot yet make. */
  pending: boolean;
}) {
  if (pending) return <Notice>Reading the comparison against the served commit…</Notice>;
  if (unavailable) {
    return (
      <p role="alert" className="text-sm text-destructive">
        The comparison against the served commit could not be read.
      </p>
    );
  }
  if (!hasBaseline) {
    return (
      <Notice>
        Nothing is currently served for this marketplace — there is no baseline, and approving
        this snapshot serves all of it.
      </Notice>
    );
  }
  if (!entry) return <Notice>Unchanged against the served commit.</Notice>;
  if (entry.binary) return <Notice>Binary file — no text diff.</Notice>;
  if (!entry.diff) return <Notice>No text diff for this entry.</Notice>;
  return (
    <div className="space-y-2">
      {entry.truncated ? (
        <p className="text-xs text-muted-foreground">Truncated: the diff was cut at its limit.</p>
      ) : null}
      <DiffText diff={entry.diff} />
    </div>
  );
}

/**
 * The reviewer's file explorer for one snapshot: a collapsible tree of the pinned commit, each
 * blob as inert text, and each file's delta against what the marketplace currently serves.
 *
 * The selection is the caller's, because the caller owns the address: `?path=` carries the
 * selected file so an approver sends the second approver a link rather than directions, and the
 * same link reopens the same bytes. Expansion is derived from the selection rather than carried
 * in the URL — it is bookkeeping, not identity.
 *
 * Inspection, not execution: Markdown is rendered without an HTML pipeline, binary blobs are
 * described, and every bound the reads impose — a cut listing, a truncated blob — is stated.
 *
 * @Requirements GW_INGEST_0032, GW_INGEST_0015, GW_INGEST_0016
 */
export function SnapshotExplorer({
  snapshotId,
  selectedPath,
  onSelect,
}: {
  snapshotId: number;
  selectedPath: string | null;
  onSelect: (path: string) => void;
}) {
  const files = useSnapshotFiles(snapshotId);
  const diff = useSnapshotDiff(snapshotId);

  const [query, setQuery] = useState("");
  // The reviewer's own expansion, kept apart from what the filter opens: clearing a filter has
  // to give back the shape they built, not leave 2000 rows open with no way down.
  const [opened, setOpened] = useState<ReadonlySet<string>>(new Set());
  const [mode, setMode] = useState<"content" | "diff">("content");

  const diffEntries = useMemo(() => diff.data?.entries ?? [], [diff.data]);
  const changes = useMemo(() => {
    const map = new Map<string, ChangeStatus>();
    for (const entry of diffEntries) {
      const path = entry.path ?? "";
      if (path && entry.type) map.set(path, entry.type);
    }
    return map;
  }, [diffEntries]);
  const tree = useMemo(
    () => buildSnapshotTree(files.data?.entries ?? [], changes),
    [files.data, changes],
  );
  const filtered = useMemo(() => filterSnapshotTree(tree, query), [tree, query]);
  const entryByPath = useMemo(
    () => new Map(diffEntries.map((entry) => [entry.path ?? "", entry])),
    [diffEntries],
  );

  // Whatever is addressed is revealed: opening a deep link must not leave the file selected but
  // buried in collapsed directories. Reviewer toggles are kept, so this only ever adds.
  useEffect(() => {
    if (!selectedPath) return;
    setOpened((open) => {
      const missing = ancestorDirectories(selectedPath).filter((path) => !open.has(path));
      if (missing.length === 0) return open;
      return new Set([...open, ...missing]);
    });
  }, [selectedPath]);

  // A filter is only useful if it shows what it found, so matches are revealed while it narrows
  // — derived, not merged into the reviewer's set, so clearing the filter undoes exactly this.
  const expanded = useMemo(
    () =>
      query.trim() === ""
        ? opened
        : new Set([...opened, ...allDirectories(filtered.nodes)]),
    [opened, query, filtered.nodes],
  );

  const toggle = (path: string) => {
    setOpened((open) => {
      const next = new Set(open);
      if (!next.delete(path)) next.add(path);
      return next;
    });
  };

  const copyAddress = async () => {
    try {
      await navigator.clipboard.writeText(window.location.href);
      toast.success("Link copied — it reopens this file for whoever you send it to.");
    } catch {
      // A denied clipboard permission is the browser's decision, not a failure to report as ours.
      toast.error("The browser would not give this page the clipboard. Copy the address bar.");
    }
  };

  const selectedNode = selectedPath ? entryByPath.get(selectedPath) : undefined;
  const selectedIsRemoved = changes.get(selectedPath ?? "") === "removed";

  // One definition: four props that have to stay in lock-step were written out twice.
  const comparison = (
    <FileDiff
      entry={selectedNode}
      hasBaseline={Boolean(diff.data?.baselineSha)}
      unavailable={diff.isError}
      pending={diff.isPending}
    />
  );


  if (files.isError) {
    return files.error instanceof ApiError && files.error.status === 403 ? (
      <Forbidden />
    ) : (
      <p role="alert" className="text-sm text-destructive">
        {files.error.message}
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-4 lg:h-full lg:min-h-0">
      {diff.isError ? (
        <p role="alert" className="text-sm text-destructive">
          The comparison against the served commit could not be read, so this tree shows the
          snapshot's own paths only: nothing here is marked added or modified, and any path this
          snapshot removes is missing from it entirely.
        </p>
      ) : null}

      {files.isLoading || diff.isPending ? (
        <Notice>Reading the snapshot's files and its comparison against the served commit…</Notice>
      ) : (files.data?.entries ?? []).length === 0 && changes.size === 0 ? (
        <Notice>This snapshot contains no files.</Notice>
      ) : (
        <div className="grid gap-4 lg:min-h-0 lg:flex-1 lg:grid-cols-[18rem_minmax(0,1fr)]">
          <div className="flex min-w-0 flex-col gap-2 rounded-lg border p-3 lg:min-h-0">
            <label htmlFor={`path-filter-${snapshotId}`} className="sr-only">
              Filter paths
            </label>
            <Input
              id={`path-filter-${snapshotId}`}
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Filter paths…"
              aria-describedby={`path-filter-count-${snapshotId}`}
            />
            <div className="flex items-center gap-2">
              <p id={`path-filter-count-${snapshotId}`} className="flex-1 text-xs text-muted-foreground">
              {/* Counted over what is shown, which includes the paths this snapshot removes;
                  the cut, when there is one, is a fact about the listing they came from. */}
              {query.trim() === ""
                ? `${filtered.searched} paths`
                : `${filtered.matched} matching of ${filtered.searched}`}
              {files.data?.truncated ? ", and the listing is cut at its limit" : ""}
              </p>
              <Button
                size="xs"
                variant="ghost"
                disabled={expanded.size === 0}
                // Keyed to what is open on screen, not to what the reviewer opened: while a
                // filter is up it owns the expansion, so collapsing means dropping it too.
                // Otherwise the control sits inert over a fully open tree.
                onClick={() => {
                  setOpened(new Set());
                  setQuery("");
                }}
              >
                Collapse all
              </Button>
            </div>
            <nav
              aria-label={`File tree of snapshot ${snapshotId}`}
              className="max-h-[60vh] overflow-y-auto lg:max-h-none lg:min-h-0 lg:flex-1"
            >
              {filtered.nodes.length === 0 ? (
                <Notice>No path matches that filter.</Notice>
              ) : (
                <SnapshotFileTree
                  nodes={filtered.nodes}
                  selectedPath={selectedPath}
                  expanded={expanded}
                  onToggle={toggle}
                  onSelect={onSelect}
                />
              )}
            </nav>
          </div>

          <div
            role="region"
            aria-label="Selected file"
            className="flex flex-col gap-3 rounded-lg border p-4 lg:min-h-0"
          >
            {selectedPath ? (
              <>
                <div className="flex flex-wrap items-center gap-2">
                  <span
                    aria-live="polite"
                    className="min-w-0 flex-1 truncate font-mono text-sm"
                  >
                    {selectedPath}
                  </span>
                  <Button
                    size="xs"
                    variant="outline"
                    aria-label={`Copy the link to ${selectedPath}`}
                    onClick={copyAddress}
                  >
                    <Link2 className="size-3.5" aria-hidden />
                    Copy link
                  </Button>
                  {selectedIsRemoved ? <Badge variant="destructive">removed</Badge> : null}
                  {selectedNode && !selectedIsRemoved ? (
                    <Badge variant="outline">{selectedNode.type}</Badge>
                  ) : null}
                  {selectedIsRemoved ? null : (
                    <div className="flex gap-2">
                      <Button
                        size="sm"
                        variant={mode === "content" ? "default" : "outline"}
                        onClick={() => setMode("content")}
                      >
                        File
                      </Button>
                      <Button
                        size="sm"
                        variant={mode === "diff" ? "default" : "outline"}
                        onClick={() => setMode("diff")}
                      >
                        <GitCompareArrows className="size-4" aria-hidden />
                        vs served
                      </Button>
                    </div>
                  )}
                </div>
                <div className="overflow-y-auto lg:min-h-0 lg:flex-1">
                  {selectedIsRemoved ? (
                    <div className="space-y-2">
                      <Notice>
                        This path is served today and is not in this snapshot — approving it
                        removes the file.
                      </Notice>
                      {comparison}
                    </div>
                  ) : mode === "content" ? (
                    <FileContent snapshotId={snapshotId} path={selectedPath} />
                  ) : (
                    comparison
                  )}
                </div>
              </>
            ) : (
              <Notice>Select a file to inspect it.</Notice>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
