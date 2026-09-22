import type { SkillCounts, SnapshotDelta as Delta } from "@/lib/snapshot-delta";

function plural(count: number, one: string, many: string): string {
  return `${count} ${count === 1 ? one : many}`;
}

/** "1 skill added, 1 modified" — the noun on the first figure only, the way people say it. */
function skillPhrase(skills: SkillCounts): string {
  const parts: [number, string][] = [
    [skills.added, "added"],
    [skills.changed, "modified"],
    [skills.moved, "moved"],
    [skills.removed, "removed"],
  ];
  const present = parts.filter(([count]) => count > 0);
  if (present.length === 0) return "no skill changed";
  return present
    .map(([count, verb], index) =>
      index === 0 ? `${plural(count, "skill", "skills")} ${verb}` : `${count} ${verb}`,
    )
    .join(", ");
}

/**
 * The card's one line: what is arriving, then how big it is, then against what.
 *
 * Skills lead because they are what an approver is deciding about; lines follow as the only sense of
 * review size on the card. The figures are deliberately **not** coloured — additions in the accent
 * and removals in red made the accent mean "text" on a diff-heavy page, and DESIGN.md already rules
 * out red-and-green for a diff. The `+` and `−` carry the meaning.
 *
 * @Requirements GW_APPROVAL_0018
 */
export function SnapshotDelta({ delta }: { delta: Delta }) {
  const parts: string[] = [];
  if (delta.skills) parts.push(skillPhrase(delta.skills));
  parts.push(
    delta.binary > 0
      ? `${plural(delta.files, "file", "files")} (${delta.binary} binary)`
      : plural(delta.files, "file", "files"),
  );
  if (delta.added > 0 || delta.removed > 0) parts.push(`+${delta.added} −${delta.removed}`);

  return (
    <p className="font-mono text-xs text-muted-foreground" data-testid="snapshot-delta">
      {parts.join(" · ")}
      {delta.baseline ? (
        <>
          {" · vs "}
          <span className="text-foreground">{delta.baseline.slice(0, 8)}</span>
        </>
      ) : (
        " · nothing served yet — approving serves all of it"
      )}
      {delta.cut ? " · the diff was cut, so these are lower bounds" : null}
    </p>
  );
}
