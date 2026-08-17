import { FileText, GitCompareArrows } from "lucide-react";
import { useState } from "react";
import {
  useSnapshotDiff,
  useSnapshotFile,
  useSnapshotFiles,
  type SnapshotDiffEntry,
} from "@/api/queries";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { MarkdownView } from "@/components/markdown-view";

function FileViewer({ snapshotId, path }: { snapshotId: number; path: string }) {
  const file = useSnapshotFile(snapshotId, path);
  if (file.isLoading) return <p className="text-sm text-muted-foreground">Loading {path}…</p>;
  if (file.isError)
    return (
      <p role="alert" className="text-sm text-destructive">
        {file.error.message}
      </p>
    );
  const content = file.data;
  if (!content) return null;
  if (content.binary) {
    return (
      <p className="text-sm text-muted-foreground">
        Binary file ({content.size} bytes) — content is not rendered.
      </p>
    );
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
      ) : (
        <pre className="overflow-x-auto rounded-md border bg-muted p-3 font-mono text-xs">
          {content.text ?? ""}
        </pre>
      )}
    </div>
  );
}

/** Unified diff text, colored by line kind with theme tokens (additions primary, removals destructive). */
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

function DiffEntryRow({ entry }: { entry: SnapshotDiffEntry }) {
  const [open, setOpen] = useState(false);
  const path = entry.path ?? "";
  return (
    <div className="rounded-md border p-2">
      <div className="flex flex-wrap items-center gap-2">
        <Badge variant={entry.type === "removed" ? "destructive" : "outline"}>{entry.type}</Badge>
        <span className="font-mono text-xs">{path}</span>
        {entry.binary ? <Badge variant="outline">binary</Badge> : null}
        {entry.truncated ? <Badge variant="outline">truncated</Badge> : null}
        {entry.diff ? (
          <Button
            size="sm"
            variant="ghost"
            className="ml-auto"
            aria-label={`${open ? "Hide" : "Show"} diff of ${path}`}
            onClick={() => setOpen(!open)}
          >
            {open ? "Hide diff" : "Show diff"}
          </Button>
        ) : null}
      </div>
      {open && entry.diff ? <DiffText diff={entry.diff} /> : null}
    </div>
  );
}

function DiffView({ snapshotId }: { snapshotId: number }) {
  const diff = useSnapshotDiff(snapshotId);
  if (diff.isLoading) return <p className="text-sm text-muted-foreground">Loading diff…</p>;
  if (diff.isError)
    return (
      <p role="alert" className="text-sm text-destructive">
        {diff.error.message}
      </p>
    );
  const entries = diff.data?.entries ?? [];
  return (
    <div className="space-y-2">
      {diff.data?.baselineSha ? (
        <p className="text-xs text-muted-foreground">
          Against served commit <span className="font-mono">{diff.data.baselineSha}</span>
        </p>
      ) : (
        <p className="text-xs text-muted-foreground">
          Nothing is currently served for this marketplace — there is no baseline, and approving
          this snapshot serves all of it.
        </p>
      )}
      {diff.data?.truncated ? (
        <p className="text-xs text-muted-foreground">Truncated: not every entry is listed.</p>
      ) : null}
      {entries.length === 0 ? (
        <p className="text-sm text-muted-foreground">No differences against the served commit.</p>
      ) : (
        entries.map((entry) => <DiffEntryRow key={`${entry.type}:${entry.path}`} entry={entry} />)
      )}
    </div>
  );
}

/**
 * Reviewer preview pane: the pinned commit's file tree, each blob as inert text, and the diff
 * against the marketplace's currently served commit. Inspection, not execution — Markdown is
 * rendered without any HTML pipeline, binary blobs are described rather than shown, and
 * truncation is stated rather than silent.
 *
 * @Requirements GW_0080, GW_0081, GW_0082
 */
export function SnapshotPreview({ snapshotId }: { snapshotId: number }) {
  const files = useSnapshotFiles(snapshotId);
  const [tab, setTab] = useState<"files" | "diff">("files");
  const [selected, setSelected] = useState<string | null>(null);

  if (files.isLoading) return <p className="text-sm text-muted-foreground">Loading file tree…</p>;
  if (files.isError)
    return (
      <p role="alert" className="text-sm text-destructive">
        {files.error.message}
      </p>
    );
  const entries = files.data?.entries ?? [];
  if (entries.length === 0)
    return <p className="text-sm text-muted-foreground">This snapshot contains no files.</p>;

  const quickOpen = entries
    .map((entry) => entry.path ?? "")
    .filter((path) => path === ".claude-plugin/marketplace.json" || path.endsWith("/SKILL.md"));
  const selectedPath = selected ?? quickOpen.find((path) => path.endsWith("/SKILL.md")) ?? null;

  return (
    <section aria-label={`Preview of snapshot ${snapshotId}`} className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <Button
          size="sm"
          variant={tab === "files" ? "default" : "outline"}
          aria-label={`Files of snapshot ${snapshotId}`}
          onClick={() => setTab("files")}
        >
          <FileText className="size-4" aria-hidden />
          Files
        </Button>
        <Button
          size="sm"
          variant={tab === "diff" ? "default" : "outline"}
          aria-label={`Diff of snapshot ${snapshotId} vs served`}
          onClick={() => setTab("diff")}
        >
          <GitCompareArrows className="size-4" aria-hidden />
          Diff vs served
        </Button>
        {files.data?.truncated ? (
          <span className="text-xs text-muted-foreground">
            Truncated: not every path is listed.
          </span>
        ) : null}
      </div>
      {tab === "diff" ? (
        <DiffView snapshotId={snapshotId} />
      ) : (
        <div className="grid gap-3 md:grid-cols-[minmax(0,18rem)_1fr]">
          <nav
            aria-label={`File tree of snapshot ${snapshotId}`}
            className="max-h-80 space-y-0.5 overflow-y-auto rounded-md border p-2"
          >
            {entries.map((entry) => {
              const path = entry.path ?? "";
              const active = path === selectedPath;
              return (
                <button
                  key={path}
                  type="button"
                  onClick={() => setSelected(path)}
                  className={`flex w-full items-center gap-2 rounded px-2 py-1 text-left font-mono text-xs hover:bg-muted ${
                    active ? "bg-muted font-semibold" : ""
                  }`}
                >
                  <span className="min-w-0 flex-1 truncate">{path}</span>
                  <span className="shrink-0 text-muted-foreground">{entry.size}</span>
                </button>
              );
            })}
          </nav>
          <div className="min-w-0 rounded-md border p-3">
            {selectedPath ? (
              <FileViewer snapshotId={snapshotId} path={selectedPath} />
            ) : (
              <p className="text-sm text-muted-foreground">Select a file to inspect it.</p>
            )}
          </div>
        </div>
      )}
    </section>
  );
}
