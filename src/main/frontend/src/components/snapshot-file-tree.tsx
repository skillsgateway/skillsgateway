import { ChevronDown, ChevronRight, File, FilePlus2, FilePen, FileX2, Folder, FolderOpen } from "lucide-react";
import type { TreeFile, TreeNode } from "@/lib/snapshot-tree";

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

/**
 * The right slot of a file row: what changed, or how big it is.
 *
 * The change is what the reviewer is deciding, so it takes the slot when there is one — a
 * status the page already knows and used to make the reviewer find by clicking. Only
 * `removed` is destructive; added and modified are facts, not objections, and stay muted.
 */
function FileMarker({ node }: { node: TreeFile }) {
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
 * The snapshot's paths as a collapsible tree.
 *
 * Built from nested lists of buttons rather than the ARIA `tree` role: a real tree widget owes
 * its users roving tabindex and the full arrow-key contract, and a half-implemented one is
 * worse for a screen reader than the disclosure pattern here, which every browser already
 * operates with Tab and Enter.
 *
 * @Requirements GW_INGEST_0032
 */
export function SnapshotFileTree({
  nodes,
  selectedPath,
  expanded,
  onToggle,
  onSelect,
}: {
  nodes: readonly TreeNode[];
  selectedPath: string | null;
  /** Paths of the directories currently open. */
  expanded: ReadonlySet<string>;
  onToggle: (path: string) => void;
  onSelect: (path: string) => void;
}) {
  return (
    <ul className="space-y-0.5">
      {nodes.map((node) =>
        node.kind === "directory" ? (
          <li key={`d:${node.path}`}>
            <button
              type="button"
              aria-expanded={expanded.has(node.path)}
              onClick={() => onToggle(node.path)}
              className="flex w-full items-center gap-1.5 rounded-md px-2 py-1 text-left text-xs hover:bg-muted outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
            >
              {expanded.has(node.path) ? (
                <ChevronDown className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
              ) : (
                <ChevronRight className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
              )}
              {expanded.has(node.path) ? (
                <FolderOpen className="size-3.5 shrink-0 text-primary" aria-hidden />
              ) : (
                <Folder className="size-3.5 shrink-0 text-primary" aria-hidden />
              )}
              <span className="min-w-0 flex-1 truncate font-medium">{node.name}</span>
            </button>
            {expanded.has(node.path) ? (
              <div className="ml-3 border-l pl-2">
                <SnapshotFileTree
                  nodes={node.children}
                  selectedPath={selectedPath}
                  expanded={expanded}
                  onToggle={onToggle}
                  onSelect={onSelect}
                />
              </div>
            ) : null}
          </li>
        ) : (
          <li key={`f:${node.path}`}>
            <button
              type="button"
              aria-current={node.path === selectedPath ? "true" : undefined}
              aria-label={
                node.status
                  ? `${node.path}, ${node.status}`
                  : `${node.path}, ${size(node.size)}`
              }
              onClick={() => onSelect(node.path)}
              // Selected is a violet tint, hover is the neutral one. They have to differ by hue
              // rather than by token, because `--accent` and `--muted` are the same value in both
              // themes — so "use a different background token" would have been a visual no-op.
              className={`flex w-full items-center gap-1.5 rounded-md px-2 py-1 text-left font-mono text-xs hover:bg-muted outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 ${
                node.path === selectedPath ? "bg-primary/10 font-semibold" : ""
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
              <span className="min-w-0 flex-1 truncate">{node.name}</span>
              <FileMarker node={node} />
            </button>
          </li>
        ),
      )}
    </ul>
  );
}
