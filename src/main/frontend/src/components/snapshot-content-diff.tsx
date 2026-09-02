import { GitCompareArrows } from "lucide-react";
import {
  useSnapshotContentDiff,
  type SnapshotContentDiff as ContentDiff,
  type SnapshotPluginDiff,
  type SnapshotSkillDiff,
} from "@/api/queries";
import { Badge } from "@/components/ui/badge";

/** A status badge reads the same for a plugin and for a skill, so one function draws both. */
function StatusBadge({ status }: { status?: string }) {
  switch (status) {
    case "added":
      return <Badge>added</Badge>;
    case "changed":
      return <Badge variant="secondary">changed</Badge>;
    case "moved":
      return <Badge variant="outline">moved</Badge>;
    case "removed":
      return <Badge variant="destructive">removed</Badge>;
    default:
      return <Badge variant="ghost">{status ?? "unchanged"}</Badge>;
  }
}

function changed(skill: SnapshotSkillDiff) {
  return skill.status !== "unchanged";
}

/** Only what changed: the inventory above already lists everything the snapshot ships. */
function changedPlugins(plugins: SnapshotPluginDiff[]) {
  return plugins
    .map((plugin) => ({ plugin, skills: (plugin.skills ?? []).filter(changed) }))
    .filter(({ plugin, skills }) => plugin.status !== "unchanged" || skills.length > 0);
}

/**
 * What to say about a plugin that is listed but has no changed skill under it. A removed plugin
 * whose skills all moved elsewhere reaches this too, and telling that reader "the manifest entry
 * changed" would be a plain lie.
 */
function emptyNote(status?: string) {
  if (status === "removed") return "The snapshot no longer declares this plugin.";
  if (status === "added") return "New plugin; it declares no skills yet.";
  return "The manifest entry changed; its skills did not.";
}

/** The counts worth stating; a zero is left out rather than shown as a zero. */
function summaryChips(summary: ContentDiff["summary"]) {
  return [
    { label: "added", count: summary?.added ?? 0 },
    { label: "changed", count: summary?.changed ?? 0 },
    { label: "moved", count: summary?.moved ?? 0 },
    { label: "removed", count: summary?.removed ?? 0 },
  ].filter((chip) => chip.count > 0);
}

/**
 * What approving this snapshot would add to what the marketplace already had approved: plugins
 * and skills marked added, changed, moved or removed against its last approved snapshot.
 *
 * Only changes are listed. The inventory it sits under already shows everything the snapshot
 * ships, and repeating the unchanged half here is exactly what buries the two new skills among
 * the forty that were approved months ago.
 *
 * Distinct from the preview pane's file diff, which is against what the facade currently serves
 * and answers in paths and hunks.
 *
 * @Requirements GW_0153
 */
export function SnapshotContentDiff({ snapshotId }: { snapshotId: number }) {
  const diff = useSnapshotContentDiff(snapshotId);

  return (
    <section aria-label={`Changes in snapshot ${snapshotId} since the last approved snapshot`}>
      <h3 className="flex items-center gap-2 text-sm font-medium">
        <GitCompareArrows className="size-4 text-primary" aria-hidden />
        Changes since the last approved snapshot
      </h3>
      {diff.isLoading ? (
        <p className="mt-2 text-sm text-muted-foreground">Loading changes…</p>
      ) : null}
      {diff.isError ? (
        <p role="alert" className="mt-2 text-sm text-destructive">
          {diff.error.message}
        </p>
      ) : null}
      {diff.data ? <Body diff={diff.data} /> : null}
    </section>
  );
}

function Body({ diff }: { diff: ContentDiff }) {
  const plugins = changedPlugins(diff.plugins ?? []);
  const chips = summaryChips(diff.summary);

  if (diff.baselineSnapshotId == null) {
    return (
      <p className="mt-2 text-sm text-muted-foreground">
        Nothing is approved for this marketplace yet, so there is no baseline to compare against.
        Approving this snapshot publishes all of it.
      </p>
    );
  }

  return (
    <div className="mt-2 space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-sm text-muted-foreground">
          Compared with approved snapshot {diff.baselineSnapshotId}
        </span>
        <span className="font-mono text-xs text-muted-foreground">
          {diff.baselineSha?.slice(0, 12)}
        </span>
        {chips.map((chip) => (
          <span key={chip.label} className="rounded-md border bg-muted px-2 py-0.5 text-xs">
            {chip.count} {chip.label}
          </span>
        ))}
      </div>
      {plugins.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          No plugin or skill changed since that snapshot was approved.
        </p>
      ) : (
        <ul className="space-y-2">
          {plugins.map(({ plugin, skills }) => (
            <li key={`${plugin.name}-${plugin.source}`} className="rounded-md border p-3">
              <div className="flex flex-wrap items-center gap-2">
                <span className="break-all font-medium">{plugin.name}</span>
                <StatusBadge status={plugin.status} />
                <span className="break-all font-mono text-xs text-muted-foreground">
                  {plugin.source}
                </span>
              </div>
              {skills.length === 0 ? (
                <p className="mt-1 text-xs text-muted-foreground">{emptyNote(plugin.status)}</p>
              ) : (
                <ul aria-label={`Changed skills in ${plugin.name}`} className="mt-2 space-y-1">
                  {skills.map((skill) => (
                    <li key={skill.path} className="flex flex-wrap items-center gap-2 text-sm">
                      <span className="break-all">{skill.name}</span>
                      <StatusBadge status={skill.status} />
                      {skill.movedFromPlugin ? (
                        <span className="text-xs text-muted-foreground">
                          from {skill.movedFromPlugin}
                        </span>
                      ) : null}
                    </li>
                  ))}
                </ul>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
