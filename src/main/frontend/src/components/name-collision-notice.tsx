import { useState } from "react";
import { CopyX } from "lucide-react";
import type { NameCollision, NameCollisionCheck } from "@/api/queries";
import { Timestamp } from "@/components/timestamp";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { WaiveForm } from "@/components/vetting-report";

function CollisionRow({ snapshotId, collision }: { snapshotId: number; collision: NameCollision }) {
  const [waiving, setWaiving] = useState(false);
  const waiver = collision.waiver;
  const incumbents = collision.incumbents ?? [];
  return (
    <li className="list-none text-sm">
      <div className="flex flex-wrap items-baseline gap-2">
        <span className={`font-mono text-xs ${waiver ? "line-through" : ""}`}>{collision.pluginName}</span>
        <span className="font-mono text-xs text-muted-foreground">{collision.location}</span>
        <span className="text-muted-foreground">
          looks like{" "}
          {incumbents
            .map((incumbent) => `${incumbent.pluginName} in ${incumbent.marketplace}`)
            .join(", ")}
        </span>
        {waiver ? (
          <Badge variant="secondary">
            waived by {waiver.approvedBy} until <Timestamp value={waiver.expiresAt} dayOnly />
          </Badge>
        ) : !waiving ? (
          <Button
            size="sm"
            variant="outline"
            aria-label={`Waive name collision for ${collision.pluginName}`}
            onClick={() => setWaiving(true)}
          >
            Waive…
          </Button>
        ) : null}
      </div>
      {waiving && !waiver && collision.finding ? (
        <WaiveForm
          snapshotId={snapshotId}
          finding={collision.finding}
          snapshotOnly
          onDone={() => setWaiving(false)}
          onCancel={() => setWaiving(false)}
        />
      ) : null}
    </li>
  );
}

/**
 * The plugin names this snapshot would introduce that look like names another marketplace already
 * serves, before the reviewer decides. A fork is accepted with a waiver on this snapshot, never on a
 * path: the finding sits in the manifest, so a path waiver would accept every future lookalike in
 * the marketplace. Renders nothing when there is nothing to say.
 *
 * @Requirements GW_APPROVAL_0021
 */
export function NameCollisionNotice({
  snapshotId,
  check,
}: {
  snapshotId: number;
  check: NameCollisionCheck | undefined;
}) {
  if (!check?.enabled) return null;
  if (check.inventoryAvailable === false) {
    return (
      <p role="alert" className="text-xs text-destructive">
        The plugin names of this snapshot could not be read, so it cannot be checked against the
        plugins other marketplaces already serve, and it cannot be approved. This is usually a storage
        error; try again later.
      </p>
    );
  }
  const collisions = check.collisions ?? [];
  if (collisions.length === 0) return null;
  return (
    <section aria-label="Plugin name collisions" className="space-y-2 rounded-md border p-3">
      <div className="flex items-center gap-2">
        <CopyX className="size-4 text-destructive" aria-hidden />
        <span className="font-medium">Plugin names already in use</span>
      </div>
      <p className="text-xs text-muted-foreground">
        {check.refused
          ? "These plugin names look like plugins another marketplace already serves. Approval is refused until each is waived. Waive only a fork you recognise, never a name you cannot account for."
          : "These plugin names look like plugins another marketplace already serves, and each has been accepted with a waiver."}
      </p>
      <ul className="space-y-2">
        {collisions.map((collision) => (
          <CollisionRow key={collision.pluginName} snapshotId={snapshotId} collision={collision} />
        ))}
      </ul>
    </section>
  );
}
