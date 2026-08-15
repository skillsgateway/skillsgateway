import { Link } from "react-router-dom";
import { useAudit, useMarketplaces, useTokens } from "@/api/queries";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { KeyRound, ScrollText, Store } from "lucide-react";

function Chip({ children }: { children: React.ReactNode }) {
  return (
    <span className="rounded-md border bg-muted px-2 py-0.5 text-xs font-medium">{children}</span>
  );
}

function SectionCard({
  icon,
  title,
  status,
  chips,
  action,
}: {
  icon: React.ReactNode;
  title: string;
  status?: string;
  chips: React.ReactNode;
  action: React.ReactNode;
}) {
  return (
    <Card>
      <CardContent className="flex items-center justify-between gap-4 py-5">
        <div className="space-y-2">
          <div className="flex items-center gap-2 font-semibold">
            <span className="text-primary">{icon}</span>
            {title}
            {status ? <Badge variant="secondary">{status}</Badge> : null}
          </div>
          <div className="flex flex-wrap gap-2">{chips}</div>
        </div>
        <div className="shrink-0">{action}</div>
      </CardContent>
    </Card>
  );
}

/**
 * Gateway overview: what is registered, what awaits review, and what has been fetched.
 *
 * @Requirements GW_0018
 */
export function OverviewPage() {
  const marketplaces = useMarketplaces();
  const tokens = useTokens();
  const audit = useAudit();

  const snapshots = (marketplaces.data ?? []).flatMap((m) => m.snapshots ?? []);
  const held = snapshots.filter((s) => s.state === "held").length;
  const approved = snapshots.filter((s) => s.state === "approved").length;
  const rejected = snapshots.filter((s) => s.state === "rejected").length;
  const activeTokens = (tokens.data ?? []).filter((t) => !t.revokedAt).length;

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Gateway Overview</h1>
      <div className="space-y-4">
        <SectionCard
          icon={<Store className="size-4" aria-hidden />}
          title="Marketplaces"
          status={held > 0 ? `${held} awaiting review` : undefined}
          chips={
            <>
              <Chip>{marketplaces.data?.length ?? "…"} marketplaces</Chip>
              <Chip>{held} held</Chip>
              <Chip>{approved} approved</Chip>
              <Chip>{rejected} rejected</Chip>
            </>
          }
          action={
            <Button render={<Link to="/marketplaces">Manage marketplaces</Link>} />
          }
        />
        <SectionCard
          icon={<ScrollText className="size-4" aria-hidden />}
          title="Fetch ledger"
          chips={<Chip>{audit.data?.length ?? "…"} recorded fetches</Chip>}
          action={
            <Button variant="outline" render={<Link to="/audit">Open audit log</Link>} />
          }
        />
        <SectionCard
          icon={<KeyRound className="size-4" aria-hidden />}
          title="Access tokens"
          chips={
            <>
              <Chip>{activeTokens} active</Chip>
              <Chip>{(tokens.data?.length ?? 0) - activeTokens} revoked</Chip>
            </>
          }
          action={
            <Button variant="outline" render={<Link to="/tokens">Manage tokens</Link>} />
          }
        />
      </div>
    </div>
  );
}
