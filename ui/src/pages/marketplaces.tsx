import { zodResolver } from "@hookform/resolvers/zod";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import { z } from "zod";
import {
  useDecideSnapshot,
  useIngest,
  useMarketplaces,
  useProvenance,
  useRegisterMarketplace,
  type MarketplaceView,
  type Snapshot,
} from "@/api/queries";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

const registerSchema = z.object({
  name: z
    .string()
    .regex(/^[a-z0-9][a-z0-9_-]*$/, "lowercase letters, digits, - and _; must not start with - or _"),
  // Scheme policy is enforced server-side (GW_0016, configurable allowlist);
  // the client only requires a well-formed absolute URL.
  url: z.url({ error: "must be a valid URL" }),
});

type RegisterForm = z.infer<typeof registerSchema>;

function stateBadge(state?: string) {
  switch (state) {
    case "approved":
      return <Badge>approved</Badge>;
    case "held":
      return <Badge variant="secondary">held</Badge>;
    case "rejected":
      return <Badge variant="destructive">rejected</Badge>;
    default:
      return <Badge variant="outline">{state ?? "unknown"}</Badge>;
  }
}

function RegisterMarketplaceDialog() {
  const [open, setOpen] = useState(false);
  const register = useRegisterMarketplace();
  const form = useForm<RegisterForm>({
    resolver: zodResolver(registerSchema),
    defaultValues: { name: "", url: "" },
  });

  const onSubmit = form.handleSubmit((values) => {
    register.mutate(values, {
      onSuccess: () => {
        toast.success(`Marketplace '${values.name}' registered`);
        form.reset();
        setOpen(false);
      },
      onError: (error) => toast.error(error.message),
    });
  });

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger render={<Button>Register marketplace</Button>} />
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Register marketplace</DialogTitle>
          <DialogDescription>
            The gateway ingests the upstream default branch; the ref is not selectable.
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={(event) => void onSubmit(event)} className="space-y-4" noValidate>
          <div className="space-y-2">
            <Label htmlFor="marketplace-name">Name</Label>
            <Input id="marketplace-name" autoComplete="off" {...form.register("name")} />
            {form.formState.errors.name ? (
              <p role="alert" className="text-sm text-destructive">
                {form.formState.errors.name.message}
              </p>
            ) : null}
          </div>
          <div className="space-y-2">
            <Label htmlFor="marketplace-url">Clone URL</Label>
            <Input
              id="marketplace-url"
              placeholder="https://github.com/org/marketplace.git"
              autoComplete="off"
              {...form.register("url")}
            />
            {form.formState.errors.url ? (
              <p role="alert" className="text-sm text-destructive">
                {form.formState.errors.url.message}
              </p>
            ) : null}
          </div>
          <DialogFooter>
            <Button type="submit" disabled={register.isPending}>
              {register.isPending ? "Registering…" : "Register"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function ProvenanceDialog({ snapshotId, onClose }: { snapshotId: number; onClose: () => void }) {
  const provenance = useProvenance(snapshotId);
  const p = provenance.data;
  return (
    <Dialog open onOpenChange={(open) => (open ? undefined : onClose())}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Provenance of snapshot {snapshotId}</DialogTitle>
          <DialogDescription>What was served, from where, and who approved it.</DialogDescription>
        </DialogHeader>
        {provenance.isLoading ? <p>Loading…</p> : null}
        {provenance.isError ? (
          <p role="alert" className="text-sm text-destructive">
            {provenance.error.message}
          </p>
        ) : null}
        {p ? (
          <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1 text-sm">
            <dt className="font-medium">Marketplace</dt>
            <dd>{p.marketplace}</dd>
            <dt className="font-medium">Upstream URL</dt>
            <dd className="break-all">{p.upstreamUrl}</dd>
            <dt className="font-medium">Upstream SHA</dt>
            <dd className="font-mono break-all">{p.upstreamSha}</dd>
            <dt className="font-medium">State</dt>
            <dd>{p.state}</dd>
            <dt className="font-medium">Ingested</dt>
            <dd>{p.ingestedAt}</dd>
            <dt className="font-medium">Decided by</dt>
            <dd>{p.decidedBy ?? "—"}</dd>
            <dt className="font-medium">Decided at</dt>
            <dd>{p.decidedAt ?? "—"}</dd>
          </dl>
        ) : null}
      </DialogContent>
    </Dialog>
  );
}

function SnapshotRow({ snapshot, onProvenance }: { snapshot: Snapshot; onProvenance: (id: number) => void }) {
  const decide = useDecideSnapshot();
  const id = snapshot.id ?? 0;
  const held = snapshot.state === "held";
  const act = (decision: "approve" | "reject") =>
    decide.mutate(
      { id, decision },
      {
        onSuccess: () => toast.success(`Snapshot ${id} ${decision === "approve" ? "approved" : "rejected"}`),
        onError: (error) => toast.error(error.message),
      },
    );
  return (
    <TableRow>
      <TableCell className="font-mono">{snapshot.sha?.slice(0, 12)}</TableCell>
      <TableCell>{stateBadge(snapshot.state)}</TableCell>
      <TableCell className="text-muted-foreground">{snapshot.violation ?? "—"}</TableCell>
      <TableCell>{snapshot.decidedBy ?? "—"}</TableCell>
      <TableCell className="space-x-2 text-right">
        {held ? (
          <>
            <Button
              size="sm"
              onClick={() => act("approve")}
              disabled={decide.isPending}
              aria-label={`Approve snapshot ${id}`}
            >
              Approve
            </Button>
            <Button
              size="sm"
              variant="destructive"
              onClick={() => act("reject")}
              disabled={decide.isPending}
              aria-label={`Reject snapshot ${id}`}
            >
              Reject
            </Button>
          </>
        ) : null}
        <Button size="sm" variant="outline" onClick={() => onProvenance(id)} aria-label={`Provenance of snapshot ${id}`}>
          Provenance
        </Button>
      </TableCell>
    </TableRow>
  );
}

function MarketplaceCard({ marketplace }: { marketplace: MarketplaceView }) {
  const ingest = useIngest();
  const [provenanceId, setProvenanceId] = useState<number | null>(null);
  const snapshots = marketplace.snapshots ?? [];
  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between">
        <div>
          <CardTitle>
            <Link to={`/marketplaces/${marketplace.name}`} className="hover:underline">
              {marketplace.name}
            </Link>
          </CardTitle>
          <CardDescription className="break-all">
            {marketplace.url}
            {marketplace.description ? <span className="block">{marketplace.description}</span> : null}
            {marketplace.upstreamUpdatedAt ? (
              <span className="block text-xs">upstream updated {marketplace.upstreamUpdatedAt}</span>
            ) : null}
          </CardDescription>
        </div>
        <Button
          variant="outline"
          onClick={() =>
            ingest.mutate(marketplace.name ?? "", {
              onSuccess: (snapshot) => toast.success(`Snapshot ${snapshot.sha?.slice(0, 12)} is ${snapshot.state}`),
              onError: (error) => toast.error(error.message),
            })
          }
          disabled={ingest.isPending}
          aria-label={`Ingest ${marketplace.name}`}
        >
          {ingest.isPending ? "Ingesting…" : "Ingest"}
        </Button>
      </CardHeader>
      <CardContent>
        {snapshots.length === 0 ? (
          <p className="text-sm text-muted-foreground">No snapshots yet — ingest to fetch the upstream default branch.</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Commit</TableHead>
                <TableHead>State</TableHead>
                <TableHead>Violation</TableHead>
                <TableHead>Decided by</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {snapshots.map((snapshot) => (
                <SnapshotRow key={snapshot.id} snapshot={snapshot} onProvenance={setProvenanceId} />
              ))}
            </TableBody>
          </Table>
        )}
        {provenanceId !== null ? (
          <ProvenanceDialog snapshotId={provenanceId} onClose={() => setProvenanceId(null)} />
        ) : null}
      </CardContent>
    </Card>
  );
}

/**
 * Marketplace administration: register, ingest, review snapshots, approve/reject.
 *
 * @Requirements GW_0018
 */
export function MarketplacesPage() {
  const marketplaces = useMarketplaces();
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Marketplaces</h1>
          <p className="text-sm text-muted-foreground">
            Registered upstreams and their quarantined, held, and approved snapshots.
          </p>
        </div>
        <RegisterMarketplaceDialog />
      </div>
      {marketplaces.isLoading ? <p>Loading…</p> : null}
      {marketplaces.isError ? (
        <p role="alert" className="text-sm text-destructive">
          {marketplaces.error.message}
        </p>
      ) : null}
      {marketplaces.data?.length === 0 ? (
        <p className="text-sm text-muted-foreground">No marketplaces registered yet.</p>
      ) : null}
      <div className="space-y-4">
        {marketplaces.data?.map((marketplace) => (
          <MarketplaceCard key={marketplace.id} marketplace={marketplace} />
        ))}
      </div>
    </div>
  );
}
