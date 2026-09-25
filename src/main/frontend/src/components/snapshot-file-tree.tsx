import { ChevronDown, ChevronRight, File, FilePlus2, FilePen, FileX2, Folder, FolderOpen } from "lucide-react";
import type { ReactNode } from "react";
import type { SnapshotTreeChild } from "@/api/queries";

const STATUS_ICON = {
  added: FilePlus2,
  modified: FilePen,
  removed: FileX2,
} as const;

/** Bytes, at the precision a reviewer reads rather than the precision the API sends. */
function size(bytes: number | undefined): string {
  if (bytes === undefined) return "—";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}

function plural(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? "" : "s"}`;
}

/**
 * The right slot of a file row: what changed, or how big it is.
 *
 * The change is what the reviewer is deciding, so it takes the slot when there is one. Only
 * `removed` is destructive; added and modified are facts, not objections, and stay muted.
 */
function FileMarker({ node }: { node: SnapshotTreeChild }) {
  if (!node.status) {
    return <span className="shrink-0 text-muted-foreground">{size(node.size)}</span>;
  }
  return (
    <span
      className={`shrink-0 text-xs uppercase ${
        node.status === "removed" ? "text-destructive" : "text-muted-foreground"
      }`}
    >
      {node.status}
    </span>
  );
}

/**
 * The right slot of a directory row: how many changes are beneath it, or how many files — so a
 * reviewer can find the change without opening every folder.
 */
function DirectoryMarker({ node }: { node: SnapshotTreeChild }) {
  const changed = node.changed ?? 0;
  return (
    <span className="shrink-0 text-muted-foreground">
      {changed > 0 ? `${changed} changed` : (node.files ?? 0)}
    </span>
  );
}

/**
 * One level of the snapshot's tree: directories as disclosures, files as selectable rows.
 *
 * A directory's contents are the caller's to render (`renderChildren`), because they are loaded
 * only when it opens — a snapshot of any size is read one folder at a time.
 *
 * Built from nested lists of buttons rather than the ARIA `tree` role: a real tree widget owes
 * its users roving tabindex and the full arrow-key contract, and a half-implemented one is
 * worse for a screen reader than the disclosure pattern here, which every browser already
 * operates with Tab and Enter.
 *
 * @Requirements GW_INGEST_0032, GW_APPROVAL_0025
 */
export function SnapshotFileTree({
  entries,
  selectedPath,
  expanded,
  onToggle,
  onSelect,
  renderChildren,
  fullPaths = false,
}: {
  entries: readonly SnapshotTreeChild[];
  selectedPath: string | null;
  /** Paths of the directories currently open. */
  expanded: ReadonlySet<string>;
  onToggle: (path: string) => void;
  onSelect: (path: string) => void;
  renderChildren: (path: string) => ReactNode;
  /** Label files with their full path rather than their name — for a flat list of search matches. */
  fullPaths?: boolean;
}) {
  return (
    <ul className="space-y-0.5">
      {entries.map((node) => {
        const path = node.path ?? "";
        const name = node.name ?? path;
        return node.kind === "directory" ? (
          <li key={`d:${path}`}>
            <button
              type="button"
              aria-expanded={expanded.has(path)}
              aria-label={`${name}, ${plural(node.files ?? 0, "file")}${
                (node.changed ?? 0) > 0 ? `, ${node.changed} changed` : ""
              }`}
              onClick={() => onToggle(path)}
              className="flex w-full items-center gap-1.5 rounded-md px-2 py-1 text-left text-xs hover:bg-muted outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
            >
              {expanded.has(path) ? (
                <ChevronDown className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
              ) : (
                <ChevronRight className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
              )}
              {expanded.has(path) ? (
                <FolderOpen className="size-3.5 shrink-0 text-primary" aria-hidden />
              ) : (
                <Folder className="size-3.5 shrink-0 text-primary" aria-hidden />
              )}
              <span className="min-w-0 flex-1 truncate font-medium">{name}</span>
              <DirectoryMarker node={node} />
            </button>
            {expanded.has(path) ? (
              <div className="ml-3 border-l pl-2">{renderChildren(path)}</div>
            ) : null}
          </li>
        ) : (
          <li key={`f:${path}`}>
            <button
              type="button"
              aria-current={path === selectedPath ? "true" : undefined}
              aria-label={node.status ? `${path}, ${node.status}` : `${path}, ${size(node.size)}`}
              onClick={() => onSelect(path)}
              // Selected is a violet tint, hover is the neutral one. They have to differ by hue
              // rather than by token, because `--accent` and `--muted` are the same value in both
              // themes — so "use a different background token" would have been a visual no-op.
              className={`flex w-full items-center gap-1.5 rounded-md px-2 py-1 text-left font-mono text-xs hover:bg-muted outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 ${
                path === selectedPath ? "bg-primary/10 font-semibold" : ""
              }`}
            >
              {(() => {
                const Icon = node.status ? STATUS_ICON[node.status] : File;
                return (
                  <Icon
                    className={`size-3.5 shrink-0 ${
                      node.status === "removed" ? "text-destructive" : "text-muted-foreground"
                    }`}
                    aria-hidden
                  />
                );
              })()}
              <span className="min-w-0 flex-1 truncate">{fullPaths ? path : name}</span>
              <FileMarker node={node} />
            </button>
          </li>
        );
      })}
    </ul>
  );
}
