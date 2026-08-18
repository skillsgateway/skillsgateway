import { zodResolver } from "@hookform/resolvers/zod";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import { z } from "zod";
import {
  formatRemaining,
  useDecideSnapshot,
  useIngest,
  useMarketplaces,
  useProvenance,
  useRegisterMarketplace,
  useSnapshotReleaseAge,
  useSnapshotVetting,
  type MarketplaceView,
  type Snapshot,
} from "@/api/queries";
import { Timestamp } from "@/components/timestamp";
import { OutcomeBadge, VettingReport } from "@/components/vetting-report";
import { GATEWAY_NAME, GATEWAY_NAME_HINT } from "@/lib/form-rules";
import { SnapshotStateBadge } from "@/components/snapshot-state";
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
    .regex(GATEWAY_NAME, "lowercase letters, digits, - and _; must not start with - or _"),
  // Scheme policy is enforced server-side (GW_0016, configurable allowlist);
  // the client only requires a well-formed absolute URL.
  url: z.url({ error: "must be a valid URL" }),
});

type RegisterForm = z.infer<typeof registerSchema>;

function RegisterMarketplaceDialog() {
  const [open, setOpen] = useState(false);
  const register = useRegisterMarketplace();
  // onChange validation is what lets Register stay disabled until both fields would be
  // accepted by the server, rather than failing on press.
  const form = useForm<RegisterForm>({
    resolver: zodResolver(registerSchema),
    defaultValues: { name: "", url: "" },
    mode: "onChange",
  });
  const canRegister = form.formState.isValid && !register.isPending;

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
            <Input
              id="marketplace-name"
              autoComplete="off"
              aria-invalid={form.formState.errors.name ? true : undefined}
              aria-describedby={
                form.formState.errors.name
                  ? "marketplace-name-hint marketplace-name-error"
                  : "marketplace-name-hint"
              }
              {...form.register("name")}
            />
            <p id="marketplace-name-hint" className="text-xs text-muted-foreground">
              {GATEWAY_NAME_HINT} It becomes the facade clone path /git/&#123;name&#125;.
            </p>
            {form.formState.errors.name ? (
              <p id="marketplace-name-error" role="alert" className="text-sm text-destructive">
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
              aria-invalid={form.formState.errors.url ? true : undefined}
              aria-describedby={
                form.formState.errors.url
                  ? "marketplace-url-hint marketplace-url-error"
                  : "marketplace-url-hint"
              }
              {...form.register("url")}
            />
            <p id="marketplace-url-hint" className="text-xs text-muted-foreground">
              A full clone URL. Register stays disabled until the name and the URL are
              both well-formed; the gateway also checks the URL scheme against its
              allowlist.
            </p>
            {form.formState.errors.url ? (
              <p id="marketplace-url-error" role="alert" className="text-sm text-destructive">
                {form.formState.errors.url.message}
              </p>
            ) : null}
          </div>
          <DialogFooter>
            <Button type="submit" disabled={!canRegister}>
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
            <dd><Timestamp value={p.ingestedAt} /></dd>
            <dt className="font-medium">Decided by</dt>
            <dd>{p.decidedBy ?? "—"}</dd>
            <dt className="font-medium">Decided at</dt>
            <dd><Timestamp value={p.decidedAt} /></dd>
          </dl>
        ) : null}
      </DialogContent>
    </Dialog>
  );
}

/**
 * The review step: the reviewer sees every connector's verdict and its findings before deciding.
 * A snapshot whose effective outcome is blocked cannot be approved from here at all — the way
 * past it is to accept each blocking finding with a scoped, expiring waiver, recorded from the
 * finding itself in the report below. The server enforces the same rule independently.
 *
 * The cooling-off window is the second reason the confirm button can be shut, and it reads
 * differently on purpose: nothing here can open it, and nothing has to — it opens by itself at the
 * stated time. The server enforces both independently.
 *
 * @Requirements GW_0042, GW_0047, GW_0073
 */
function ApproveDialog({ snapshotId, onClose }: { snapshotId: number; onClose: () => void }) {
  const vetting = useSnapshotVetting(snapshotId);
  const releaseAge = useSnapshotReleaseAge(snapshotId);
  const decide = useDecideSnapshot();
  const blocked = vetting.data?.outcome === "BLOCKED" || vetting.data?.outcome === undefined;
  const tooYoung = releaseAge.data?.eligible === false;
  const remaining = formatRemaining(releaseAge.data?.remainingSeconds ?? 0);

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
        <DialogFooter>
          <Button
            disabled={decide.isPending || blocked || tooYoung}
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

/** The chain outcome next to the snapshot's own state, so the table shows both at a glance. */
function VettingOutcomeCell({ snapshotId }: { snapshotId: number }) {
  const vetting = useSnapshotVetting(snapshotId);
  if (vetting.isLoading) return <span className="text-xs text-muted-foreground">…</span>;
  if (vetting.isError) return <span className="text-xs text-muted-foreground">unavailable</span>;
  return <OutcomeBadge outcome={vetting.data?.outcome} />;
}

function SnapshotRow({ snapshot, onProvenance }: { snapshot: Snapshot; onProvenance: (id: number) => void }) {
  const decide = useDecideSnapshot();
  const [approving, setApproving] = useState(false);
  const id = snapshot.id ?? 0;
  // A revoked snapshot is decidable again: the retraction was made without a person, so a person
  // has to be able to answer it — by re-approving behind the same gate, or by rejecting for good.
  const decidable = snapshot.state === "held" || snapshot.state === "revoked";
  // Asked only for a snapshot someone could decide on, and only about approval: rejecting is never
  // gated by age.
  const releaseAge = useSnapshotReleaseAge(decidable ? id : null);
  const tooYoung = releaseAge.data?.eligible === false;
  const remaining = formatRemaining(releaseAge.data?.remainingSeconds ?? 0);
  const act = (decision: "reject") =>
    decide.mutate(
      { id, decision },
      {
        onSuccess: () => toast.success(`Snapshot ${id} rejected`),
        onError: (error) => toast.error(error.message),
      },
    );
  return (
    <TableRow>
      <TableCell className="font-mono">{snapshot.sha?.slice(0, 12)}</TableCell>
      <TableCell><SnapshotStateBadge state={snapshot.state} /></TableCell>
      <TableCell>
        <VettingOutcomeCell snapshotId={id} />
      </TableCell>
      <TableCell className="text-muted-foreground">{snapshot.violation ?? "—"}</TableCell>
      <TableCell>{snapshot.decidedBy ?? "—"}</TableCell>
      <TableCell className="space-x-2 text-right">
        {decidable ? (
          <>
            <Button
              size="sm"
              onClick={() => setApproving(true)}
              disabled={decide.isPending || tooYoung}
              aria-label={`Approve snapshot ${id}`}
              title={
                tooYoung
                  ? `Inside the minimum release age; eligible in ${remaining}`
                  : undefined
              }
            >
              {tooYoung
                ? `Eligible in ${remaining}`
                : snapshot.state === "revoked"
                  ? "Re-approve"
                  : "Approve"}
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
        {approving ? <ApproveDialog snapshotId={id} onClose={() => setApproving(false)} /> : null}
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
              <span className="block text-xs">upstream updated <Timestamp value={marketplace.upstreamUpdatedAt} /></span>
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
                <TableHead>Vetting</TableHead>
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
