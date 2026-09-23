import { toast } from "sonner";
import {
  describeFourEyesConflicts,
  formatRemaining,
  useDecideSnapshot,
  useSnapshotFourEyes,
  useSnapshotNameCollisions,
  useSnapshotReleaseAge,
  useSnapshotVetting,
} from "@/api/queries";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { NameCollisionNotice } from "@/components/name-collision-notice";
import { VettingReport } from "@/components/vetting-report";

/**
 * The review step: the reviewer sees every vetter's verdict and its findings before deciding.
 * A snapshot whose effective outcome is blocked cannot be approved from here at all — the way
 * past it is to accept each blocking finding with a scoped, expiring waiver, recorded from the
 * finding itself in the report below. The server enforces the same rule independently.
 *
 * The cooling-off window is the second reason the confirm button can be shut, and it reads
 * differently on purpose: nothing here can open it, and nothing has to — it opens by itself at the
 * stated time. The server enforces both independently.
 *
 * Separation of duties is the third reason, and the only one the reviewer cannot resolve by doing
 * something to the snapshot: what disqualifies them is what they already did to it. Under warn —
 * the default — it says so and lets them through, because a single-administrator deployment has
 * nobody else to ask; under enforce it shuts the button and names the person who has to press it
 * instead. The server enforces all three independently.
 *
 * A plugin name that looks like one another marketplace already serves shuts it too, until the
 * reviewer waives it on this snapshot — the same act as accepting a vetting finding.
 *
 * @Requirements GW_VETTING_0005, GW_VETTING_0010, GW_APPROVAL_0004.4, GW_APPROVAL_0010, GW_APPROVAL_0011, GW_APPROVAL_0021
 */
export function ApproveDialog({ snapshotId, onClose }: { snapshotId: number; onClose: () => void }) {
  const vetting = useSnapshotVetting(snapshotId);
  const releaseAge = useSnapshotReleaseAge(snapshotId);
  const fourEyes = useSnapshotFourEyes(snapshotId);
  const nameCollisions = useSnapshotNameCollisions(snapshotId);
  const collides = nameCollisions.data?.refused === true;
  const decide = useDecideSnapshot();
  const blocked = vetting.data?.outcome === "blocked" || vetting.data?.outcome === undefined;
  const tooYoung = releaseAge.data?.eligible === false;
  const remaining = formatRemaining(releaseAge.data?.remainingSeconds ?? 0);
  const conflicted = (fourEyes.data?.conflicts ?? []).length > 0;
  const refused = fourEyes.data?.refused === true;
  const conflictSummary = describeFourEyesConflicts(fourEyes.data);

  return (
    <Dialog open onOpenChange={(open) => (open ? undefined : onClose())}>
      {/* Wider than the default: the review surface carries findings, their locations, and the
          waiver form beside each one. */}
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Approve snapshot {snapshotId}</DialogTitle>
          <DialogDescription>
            Approving publishes this snapshot to the git facade. Review the vetting verdicts first.
          </DialogDescription>
        </DialogHeader>
        <VettingReport snapshotId={snapshotId} />
        <NameCollisionNotice snapshotId={snapshotId} check={nameCollisions.data} />
        {blocked ? (
          <p className="text-xs text-muted-foreground">
            The vetting chain did not clear this snapshot. Waive each blocking finding above — with
            a justification and an expiry — and the approval unblocks. Every waiver is recorded in
            the audit ledger with your identity.
          </p>
        ) : null}
        {tooYoung ? (
          <p className="text-xs text-muted-foreground">
            This snapshot is inside the cooling-off window: the gateway first ingested its commit
            less than the configured minimum release age ago. It becomes approvable in {remaining},
            with nothing to do in the meantime. The age is counted from the gateway's own first
            sighting, not from the commit's timestamp.
          </p>
        ) : null}
        {conflicted ? (
          <p
            role={refused ? "alert" : undefined}
            className={refused ? "text-xs text-destructive" : "text-xs text-muted-foreground"}
          >
            {refused
              ? `Four-eyes rule: you ${conflictSummary}, so this approval is refused. Someone else with
                 approval rights in this marketplace has to make the decision — approving content you
                 supplied yourself is exactly what the rule exists to prevent.`
              : `Four-eyes rule: you ${conflictSummary}. Approving is still allowed, but this will be
                 recorded in the audit ledger as a self-approval. An independent reviewer is what the
                 gate is worth.`}
          </p>
        ) : null}
        <DialogFooter>
          <Button
            disabled={decide.isPending || blocked || collides || tooYoung || refused}
            aria-label={`Confirm approval of snapshot ${snapshotId}`}
            onClick={() =>
              decide.mutate(
                { id: snapshotId, decision: "approve" },
                {
                  onSuccess: () => {
                    toast.success(`Snapshot ${snapshotId} approved`);
                    onClose();
                  },
                  onError: (error) => toast.error(error.message),
                },
              )
            }
          >
            {decide.isPending ? "Approving…" : "Approve"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
