import { GitCompareArrows, Link2, ShieldAlert } from "lucide-react";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { formatJson } from "@/lib/json-format";
import { ApiError } from "@/api/client";
import {
  useSnapshotDirectory,
  useSnapshotFile,
  useSnapshotFileDiff,
  useSnapshotPathSearch,
  type SnapshotDiffEntry,
  type SnapshotTreeChild,
} from "@/api/queries";
import { MarkdownView } from "@/components/markdown-view";
import { SegmentedGroup } from "@/components/segmented-group";
import { SnapshotFileTree } from "@/components/snapshot-file-tree";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ancestorDirectories } from "@/lib/snapshot-tree";

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
export function DiffText({ diff }: { diff: string }) {
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

/** How long typing settles before a search is sent: one request per pause, not per keystroke. */
const SEARCH_SETTLE_MS = 250;

/** "Show 500 more of 601" — the rest of a paged listing, on request, with its true size. */
function ShowMore({
  shown,
  total,
  pageSize,
  loading,
  onMore,
}: {
  shown: number;
  total: number;
  pageSize: number;
  loading: boolean;
  onMore: () => void;
}) {
  return (
    <Button size="xs" variant="ghost" className="mt-1" disabled={loading} onClick={onMore}>
      {loading
        ? "Loading…"
        : `Show ${Math.min(pageSize, total - shown)} more (${shown} of ${total} shown)`}
    </Button>
  );
}

type TreeProps = {
  snapshotId: number;
  selectedPath: string | null;
  expanded: ReadonlySet<string>;
  onToggle: (path: string) => void;
  onSelect: (path: string) => void;
};

/** One directory's children, read when it is opened; a directory wider than a page pages on request. */
function DirectoryChildren({ dir, ...tree }: TreeProps & { dir: string }) {
  const listing = useSnapshotDirectory(tree.snapshotId, dir);
  if (listing.isPending) {
    return (
      <p role="status" className="px-2 py-1 text-xs text-muted-foreground">
        Loading {dir === "" ? "the snapshot" : dir}…
      </p>
    );
  }
  if (listing.isError) {
    return (
      <p role="alert" className="px-2 py-1 text-xs text-destructive">
        {listing.error.message}
      </p>
    );
  }
  const entries: SnapshotTreeChild[] = listing.data.pages.flatMap((page) => page.entries ?? []);
  const total = listing.data.pages[0]?.total ?? entries.length;
  return (
    <>
      <SnapshotFileTree
        entries={entries}
        {...tree}
        renderChildren={(path) => <DirectoryChildren dir={path} {...tree} />}
      />
      {listing.hasNextPage ? (
        <ShowMore
          shown={entries.length}
          total={total}
          pageSize={TREE_PAGE}
          loading={listing.isFetchingNextPage}
          onMore={() => void listing.fetchNextPage()}
        />
      ) : null}
    </>
  );
}

/** Children per page of `GET /snapshots/{id}/tree`, for the "Show more" label. */
const TREE_PAGE = 500;
/** Paths per page of `GET /snapshots/{id}/files`. */
const FILES_PAGE = 2000;

/** Search matches from anywhere in the snapshot, as a flat list of full paths. */
function SearchResults({ query, ...tree }: TreeProps & { query: string }) {
  const search = useSnapshotPathSearch(tree.snapshotId, query);
  if (search.isPending) return <Notice>Searching…</Notice>;
  if (search.isError) {
    return (
      <p role="alert" className="text-sm text-destructive">
        {search.error.message}
      </p>
    );
  }
  const entries: SnapshotTreeChild[] = search.data.pages.flatMap((page) =>
    (page.entries ?? []).map((entry) => ({ kind: "file", path: entry.path, size: entry.size })),
  );
  if (entries.length === 0) return <Notice>No path matches that search.</Notice>;
  const total = search.data.pages[0]?.total ?? entries.length;
  return (
    <>
      <SnapshotFileTree
        entries={entries}
        {...tree}
        fullPaths
        renderChildren={() => null}
      />
      {search.hasNextPage ? (
        <ShowMore
          shown={entries.length}
          total={total}
          pageSize={FILES_PAGE}
          loading={search.isFetchingNextPage}
          onMore={() => void search.fetchNextPage()}
        />
      ) : null}
    </>
  );
}

/** The count line under the search box: true totals, over the whole snapshot. */
function countLine(
  root: { files?: number; changed?: number; baselineSha?: string } | undefined,
  searching: boolean,
  matches: number | undefined,
): string {
  const files = root?.files ?? 0;
  const fileCount = `${files} ${files === 1 ? "file" : "files"}`;
  if (searching) return matches === undefined ? `Searching ${fileCount}…` : `${matches} matching of ${fileCount}`;
  // With nothing served every path is new, so a changed count would only repeat the file count.
  return root?.baselineSha ? `${fileCount}, ${root.changed ?? 0} changed` : fileCount;
}

/**
 * The reviewer's file explorer for one snapshot: the pinned commit's tree, read one folder at a
 * time, each blob as inert text, and each file's delta against what the marketplace currently
 * serves. A snapshot of any size can be read to its end: folders load when opened, a folder
 * wider than a page shows the rest on request, and the search runs on the gateway over every path.
 *
 * The selection is the caller's, because the caller owns the address: `?path=` carries the
 * selected file so an approver sends the second approver a link rather than directions, and the
 * same link reopens the same bytes. Expansion is derived from the selection rather than carried
 * in the URL — it is bookkeeping, not identity.
 *
 * Inspection, not execution: Markdown is rendered without an HTML pipeline, binary blobs are
 * described, and every bound the reads impose — a page of a listing, a truncated blob — is stated.
 *
 * @Requirements GW_INGEST_0032, GW_INGEST_0015, GW_INGEST_0016, GW_APPROVAL_0025, GW_APPROVAL_0026, GW_APPROVAL_0027
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
  const root = useSnapshotDirectory(snapshotId, "");
  const fileDiff = useSnapshotFileDiff(snapshotId, selectedPath);

  const [query, setQuery] = useState("");
  const [settled, setSettled] = useState("");
  const search = useSnapshotPathSearch(snapshotId, settled);
  // The reviewer's own expansion. A search replaces the tree rather than opening it, so clearing
  // a search gives back exactly the shape they built.
  const [opened, setOpened] = useState<ReadonlySet<string>>(new Set());
  const [mode, setMode] = useState<"content" | "diff">("content");

  useEffect(() => {
    const timer = setTimeout(() => setSettled(query.trim()), SEARCH_SETTLE_MS);
    return () => clearTimeout(timer);
  }, [query]);

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

  // The one-path diff answers for this path only; anything else in it would be a stale answer.
  const selectedNode: SnapshotDiffEntry | undefined = fileDiff.data?.entries?.find(
    (entry) => entry.path === selectedPath,
  );
  const selectedIsRemoved = selectedNode?.type === "removed";

  // One definition: four props that have to stay in lock-step were written out twice.
  const comparison = (
    <FileDiff
      entry={selectedNode}
      hasBaseline={Boolean(fileDiff.data?.baselineSha)}
      unavailable={fileDiff.isError}
      pending={fileDiff.isPending}
    />
  );

  if (root.isError) {
    return root.error instanceof ApiError && root.error.status === 403 ? (
      <Forbidden />
    ) : (
      <p role="alert" className="text-sm text-destructive">
        {root.error.message}
      </p>
    );
  }

  const totals = root.data?.pages[0];
  const searching = query.trim() !== "";
  const tree: TreeProps = {
    snapshotId,
    selectedPath,
    expanded: opened,
    onToggle: toggle,
    onSelect,
  };

  return (
    <div className="flex flex-col gap-4 lg:h-full lg:min-h-0">
      {root.isPending ? (
        <Notice>Reading the snapshot's files and its comparison against the served commit…</Notice>
      ) : (totals?.files ?? 0) === 0 && (totals?.changed ?? 0) === 0 ? (
        <Notice>This snapshot contains no files.</Notice>
      ) : (
        <div className="grid gap-4 lg:min-h-0 lg:flex-1 lg:grid-cols-[18rem_minmax(0,1fr)]">
          <div className="flex min-w-0 flex-col gap-2 rounded-lg border p-3 lg:min-h-0">
            <label htmlFor={`path-filter-${snapshotId}`} className="sr-only">
              Search paths
            </label>
            <Input
              id={`path-filter-${snapshotId}`}
              type="search"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Search paths…"
              aria-describedby={`path-filter-count-${snapshotId}`}
            />
            <div className="flex items-center gap-2">
              <p
                id={`path-filter-count-${snapshotId}`}
                aria-live="polite"
                className="flex-1 text-xs text-muted-foreground"
              >
                {countLine(
                  totals,
                  searching,
                  searching && settled === query.trim() ? search.data?.pages[0]?.total : undefined,
                )}
              </p>
              <Button
                size="xs"
                variant="ghost"
                disabled={opened.size === 0 && !searching}
                // Collapsing means dropping the search too: while it is up it owns the pane, and
                // the control would otherwise sit inert over it.
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
              {searching ? (
                settled === query.trim() ? (
                  <SearchResults query={settled} {...tree} />
                ) : (
                  <Notice>Searching…</Notice>
                )
              ) : (
                <DirectoryChildren dir="" {...tree} />
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
