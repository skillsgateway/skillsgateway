import { useState } from "react";
import { toast } from "sonner";
import { useChangeMarketplaceUrl, useEstateReport } from "@/api/queries";
import { isDeclared } from "@/components/remove-marketplace";
import { Button } from "@/components/ui/button";
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

/**
 * Whether the URL carries a credential in its authority, read as the server reads it: anything
 * between "://" and the next "/" holding an "@".
 */
function embedsCredential(url: string): boolean {
  const start = url.indexOf("://");
  if (start < 0) return false;
  const end = url.indexOf("/", start + 3);
  return url.slice(start + 3, end < 0 ? url.length : end).includes("@");
}

/**
 * The correction itself. The scheme allowlist and the upstream read are the server's: it owns the
 * configured schemes and the network, so a refusal is shown here as the server states it, with the
 * stored URL untouched.
 */
export function EditMarketplaceUrlDialog({
  name,
  current,
  onClose,
}: {
  name: string;
  current: string;
  onClose: () => void;
}) {
  const change = useChangeMarketplaceUrl();
  const [url, setUrl] = useState(current);
  const trimmed = url.trim();
  const credential = embedsCredential(trimmed);
  const valid = trimmed !== "" && !credential;

  const onSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!valid) return;
    change.mutate(
      { name, url: trimmed },
      {
        onSuccess: (changed) => {
          toast.success(`URL of '${name}' changed`);
          for (const warning of changed.warnings ?? []) toast.warning(warning);
          onClose();
        },
      },
    );
  };

  return (
    <Dialog open onOpenChange={(open) => (open || change.isPending ? undefined : onClose())}>
      <DialogContent>
        <form onSubmit={onSubmit} className="space-y-4">
          <DialogHeader>
            <DialogTitle className="break-all">Correct the URL of {name}</DialogTitle>
            <DialogDescription>
              Possible until the first snapshot. The upstream is read before anything changes, and you become
              the marketplace&apos;s registrant, so you cannot approve its snapshots yourself.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <Label htmlFor="marketplace-url">Clone URL</Label>
            <Input
              id="marketplace-url"
              value={url}
              onChange={(event) => {
                setUrl(event.target.value);
                change.reset();
              }}
              autoComplete="off"
              spellCheck={false}
              className="font-mono text-xs"
              aria-describedby="marketplace-url-hint"
              aria-invalid={credential || change.isError}
            />
            <p id="marketplace-url-hint" className="text-xs text-muted-foreground">
              A clone URL without a credential in it. The gateway checks its scheme and reads the upstream when you
              save.
            </p>
            {credential ? (
              <p role="alert" className="text-sm text-destructive">
                Remove the credential: upstream credentials are configured on the gateway, not in the URL.
              </p>
            ) : null}
            {change.isError ? (
              <p role="alert" className="text-sm text-destructive break-words">
                {change.error.message}
              </p>
            ) : null}
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose} disabled={change.isPending}>
              Cancel
            </Button>
            <Button type="submit" disabled={!valid || change.isPending}>
              {change.isPending ? "Saving…" : "Save URL"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Correcting an upstream marketplace's URL, in its Upstream settings, for an administrator. Offered
 * only while it has no snapshot; after that the URL is the snapshots' source of record and the card
 * says what to do instead. A declared marketplace's URL belongs to its declaration, which the next
 * reconciliation applies.
 *
 * @Requirements GW_INGEST_0067
 */
export function EditMarketplaceUrl({
  name,
  url,
  snapshotCount,
}: {
  name: string;
  url: string;
  snapshotCount: number;
}) {
  const report = useEstateReport();
  const [open, setOpen] = useState(false);
  const declared = isDeclared(report.data, name);

  if (snapshotCount > 0) {
    return (
      <p className="text-xs text-muted-foreground">
        The URL is the source of record for this marketplace&apos;s snapshots and can no longer change. To take
        content from another upstream, remove the marketplace and register it again.
      </p>
    );
  }
  return (
    <div className="space-y-2">
      <Button
        variant="outline"
        size="sm"
        onClick={() => setOpen(true)}
        disabled={declared || report.isLoading}
        aria-describedby={declared ? "url-declared" : undefined}
      >
        Edit URL…
      </Button>
      {declared ? (
        <p id="url-declared" className="text-xs text-muted-foreground">
          This marketplace is declared in the estate configuration. Correct the URL in the declaration and
          restart the gateway; the reconciliation applies it while there is no snapshot.
        </p>
      ) : null}
      {open ? <EditMarketplaceUrlDialog name={name} current={url} onClose={() => setOpen(false)} /> : null}
    </div>
  );
}
