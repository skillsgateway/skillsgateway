import { useState } from "react";
import { toast } from "sonner";
import { useMarketplaceVettingChain, useToggleVetter } from "@/api/queries";
import { VettingFlow } from "@/components/vetting-flow";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { marketplaceFlow, marketplaceHeadline, type FlowNode } from "@/lib/vetting-flow";

/**
 * The switch itself, inside the node it belongs to. A reason is optional — the server accepts a
 * toggle without one — so the control is never disabled for want of it; the hint says what the
 * note is for rather than pretending it is required.
 */
function ToggleControls({ node, marketplace }: { node: FlowNode; marketplace: string }) {
  const toggle = useToggleVetter();
  const [reason, setReason] = useState("");
  const vetter = node.setting?.name ?? "";
  const enabled = node.setting?.enabled === true;
  const next = !enabled;
  const hintId = `vetter-toggle-hint-${vetter}`;

  return (
    <div className="mt-2 space-y-2 rounded-md border bg-muted/40 p-3">
      <div className="space-y-1">
        <Label htmlFor={`vetter-reason-${vetter}`}>Reason (optional)</Label>
        <Input
          id={`vetter-reason-${vetter}`}
          autoComplete="off"
          value={reason}
          aria-describedby={hintId}
          onChange={(event) => setReason(event.target.value)}
          placeholder={next ? "Why should this vetter run here?" : "Why is this vetter off here?"}
        />
      </div>
      <p id={hintId} className="text-xs text-muted-foreground">
        The change is scoped to this marketplace and overrides the global setting. It is recorded on
        the audit ledger with your identity, the vetter, the scope, the new state and this note.
      </p>
      <Button
        size="sm"
        variant={next ? "default" : "outline"}
        disabled={toggle.isPending}
        aria-label={`${next ? "Enable" : "Disable"} ${vetter} for ${marketplace}`}
        onClick={() =>
          toggle.mutate(
            { vetter, marketplace, enabled: next, reason: reason.trim() || undefined },
            {
              onSuccess: () => {
                toast.success(`${vetter} ${next ? "enabled" : "disabled"} for ${marketplace}`);
                setReason("");
              },
              onError: (error) => toast.error(error.message),
            },
          )
        }
      >
        {toggle.isPending
          ? next
            ? "Enabling…"
            : "Disabling…"
          : next
            ? "Enable for this marketplace"
            : "Disable for this marketplace"}
      </Button>
    </div>
  );
}

/**
 * The effective vetting chain of one marketplace, drawn the way a snapshot's chain is drawn but
 * without verdicts: what will run, whether each vetter is on, and which setting decided that.
 *
 * Administrator-only. The caller decides whether to mount it; the server refuses the read to
 * anyone else regardless (GW_VETTING_0029.4), which is what actually protects it.
 *
 * @Requirements GW_VETTING_0029.5
 */
export function MarketplaceVettingChain({ marketplace }: { marketplace: string }) {
  const chain = useMarketplaceVettingChain(marketplace);
  const flow = chain.data ? marketplaceFlow(chain.data) : [];

  return (
    <Card>
      <CardHeader>
        <CardTitle>Vetting chain</CardTitle>
        <CardDescription>
          What runs against every snapshot of this marketplace, in order, and where each vetter's
          state comes from. Switching one off narrows the evidence behind an approval; it never makes
          one automatic.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {chain.isLoading ? <p className="text-sm text-muted-foreground">Loading the chain…</p> : null}
        {chain.isError ? (
          <p role="alert" className="text-sm text-destructive">
            {chain.error.message}
          </p>
        ) : null}
        {chain.data ? (
          <VettingFlow
            label={`Vetting chain of ${marketplace}`}
            headline={marketplaceHeadline(flow)}
            nodes={flow}
            detailFooter={(node) =>
              node.kind === "setting" ? <ToggleControls node={node} marketplace={marketplace} /> : null
            }
          />
        ) : null}
      </CardContent>
    </Card>
  );
}
