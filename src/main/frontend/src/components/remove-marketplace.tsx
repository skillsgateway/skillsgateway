import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { useEstateReport, useRemoveMarketplace, type EstateReconciliation } from "@/api/queries";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

/** A name has no length limit and no spaces, so a button carrying it must be allowed to wrap anywhere. */
const WRAPS = "h-auto min-h-8 max-w-full whitespace-normal break-all py-1.5";

/** Declared by the estate when the last reconciliation has an entry for it, whatever that entry's outcome. */
export function isDeclared(report: EstateReconciliation | undefined, name: string): boolean {
  return (report?.entries ?? []).some((entry) => entry.kind === "marketplace" && entry.name === name);
}

/**
 * The confirmation: what removal does, and the reason the server requires. The server refuses a
 * blank reason, and that is the only rule on it, so it is the only rule here.
 */
export function RemoveMarketplaceDialog({ name, onClose }: { name: string; onClose: () => void }) {
  const remove = useRemoveMarketplace();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [reason, setReason] = useState("");
  const trimmed = reason.trim();

  const onSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!trimmed) return;
    remove.mutate(
      { name, reason: trimmed },
      {
        onSuccess: (removal) => {
          const withdrawn = removal.withdrawnSnapshotIds?.length ?? 0;
          toast.success(
            `Marketplace '${name}' removed; ${withdrawn} approved snapshot${withdrawn === 1 ? "" : "s"} withdrawn`,
          );
          // Leave first: refreshed while still on its page, the page would read "not found".
          void navigate("/marketplaces");
          void queryClient.invalidateQueries({ queryKey: ["marketplaces"] });
        },
        onError: (error) => toast.error(error.message),
      },
    );
  };

  return (
    <Dialog open onOpenChange={(open) => (open || remove.isPending ? undefined : onClose())}>
      <DialogContent>
        <form onSubmit={onSubmit} className="space-y-4">
          <DialogHeader>
            <DialogTitle className="break-all">Remove {name}</DialogTitle>
            <DialogDescription>This cannot be undone.</DialogDescription>
          </DialogHeader>
          <ul className="list-disc space-y-1 pl-5 text-sm">
            <li>Every approved snapshot is withdrawn, so the git facade serves nothing under this name.</li>
            <li>Tokens lose their grant to publish to it; grants to fetch it are kept.</li>
            <li>Its record, its snapshots and the audit ledger are kept.</li>
            <li>The name can then be registered again, as a new marketplace that inherits nothing.</li>
          </ul>
          <div className="space-y-2">
            <Label htmlFor="removal-reason">Reason</Label>
            <Input
              id="removal-reason"
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              autoComplete="off"
              placeholder="upstream moved to a new URL"
              aria-describedby="removal-reason-hint"
            />
            <p id="removal-reason-hint" className="text-xs text-muted-foreground">
              A reason is required. It is recorded on the removal and on every withdrawal it makes.
            </p>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose} disabled={remove.isPending}>
              Cancel
            </Button>
            <Button type="submit" variant="destructive" className={WRAPS} disabled={!trimmed || remove.isPending}>
              {remove.isPending ? "Removing…" : `Remove ${name}`}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Removing a marketplace, on its settings, for an administrator. A marketplace the estate
 * declares is not offered: the next reconciliation would register the name again. The report
 * failing to load leaves the control available — the server permits the removal, and is the
 * authority on it.
 *
 * @Requirements GW_INGEST_0048, GW_INGEST_0049
 */
export function RemoveMarketplace({ name }: { name: string }) {
  const report = useEstateReport();
  const [open, setOpen] = useState(false);
  const declared = isDeclared(report.data, name);

  return (
    <Card>
      <CardHeader>
        <CardTitle>Remove marketplace</CardTitle>
        <CardDescription>
          Withdraws everything this marketplace serves and retires it. The record and the ledger are kept.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-2">
        <Button
          variant="destructive"
          className={WRAPS}
          onClick={() => setOpen(true)}
          disabled={declared || report.isLoading}
          aria-describedby={declared ? "removal-declared" : undefined}
        >
          Remove {name}…
        </Button>
        {declared ? (
          <p id="removal-declared" className="text-xs text-muted-foreground">
            This marketplace is declared in the estate configuration, so the next reconciliation would
            register the name again as a new, empty marketplace. Remove the declaration and restart the
            gateway first.
          </p>
        ) : null}
      </CardContent>
      {open ? <RemoveMarketplaceDialog name={name} onClose={() => setOpen(false)} /> : null}
    </Card>
  );
}
