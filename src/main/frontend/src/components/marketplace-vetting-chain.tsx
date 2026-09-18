import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
  useMarketplaceChainSettings,
  useMarketplaceVettingChain,
} from "@/api/queries";
import { VettingFlow } from "@/components/vetting-flow";
import {
  ModeControls,
  OrderControls,
  ToggleControls,
  reorder,
} from "@/components/vetting-chain-controls";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { marketplaceFlow, marketplaceHeadline } from "@/lib/vetting-flow";

/**
 * The effective vetting chain of one marketplace, drawn the way a snapshot's chain is drawn but
 * without verdicts: what will run, in what order, how far, whether each vetter is on, and which
 * setting decided each of those.
 *
 * Administrator-only. The caller decides whether to mount it; the server refuses the read to
 * anyone else regardless (GW_VETTING_0029.4), which is what actually protects it.
 *
 * @Requirements GW_VETTING_0029.5
 * @Requirements GW_VETTING_0034
 */
export function MarketplaceVettingChain({ marketplace }: { marketplace: string }) {
  const chain = useMarketplaceVettingChain(marketplace);
  const settings = useMarketplaceChainSettings(marketplace);
  const [pendingOrder, setPendingOrder] = useState<string[] | null>(null);

  // A saved order, or another administrator's, replaces what this page was proposing: the drawing
  // must never keep showing an arrangement that is no longer on offer.
  useEffect(() => setPendingOrder(null), [marketplace, settings.data?.order]);

  const nodes = chain.data ? marketplaceFlow(chain.data) : [];
  const flow = pendingOrder === null ? nodes : reorder(nodes, pendingOrder);

  return (
    <Card>
      <CardHeader>
        <CardTitle>Vetting chain</CardTitle>
        <CardDescription>
          What runs against every snapshot of this marketplace, in order, how far the chain goes, and
          where each setting comes from. Narrowing or shortening the chain narrows the evidence
          behind an approval; it never makes one automatic.{" "}
          {/* Every setting here resolves from the estate's default when this marketplace overrides
              nothing, so the page that governs that default is one hop away rather than folklore. */}
          <Link to="/vetting" className="font-medium text-primary underline-offset-4 hover:underline">
            Govern the chain across the estate
          </Link>
          .
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {chain.isLoading || settings.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading the chain…</p>
        ) : null}
        {chain.isError ? (
          <p role="alert" className="text-sm text-destructive">
            {chain.error.message}
          </p>
        ) : null}
        {settings.isError ? (
          <p role="alert" className="text-sm text-destructive">
            {settings.error.message}
          </p>
        ) : null}
        {chain.data ? (
          <VettingFlow
            label={`Vetting chain of ${marketplace}`}
            headline={marketplaceHeadline(flow, settings.data?.mode)}
            nodes={flow}
            detailFooter={(node) =>
              node.kind === "setting" ? <ToggleControls node={node} marketplace={marketplace} /> : null
            }
          />
        ) : null}
        {settings.data ? (
          <div className="grid gap-3 md:grid-cols-2">
            <ModeControls settings={settings.data} marketplace={marketplace} />
            <OrderControls
              settings={settings.data}
              marketplace={marketplace}
              pending={pendingOrder}
              onPending={setPendingOrder}
            />
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}
