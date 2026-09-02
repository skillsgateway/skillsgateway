import { zodResolver } from "@hookform/resolvers/zod";
import {
  type ColumnDef,
  type ExpandedState,
  type SortingState,
  columnVisibilityFeature,
  createExpandedRowModel,
  createSortedRowModel,
  flexRender,
  rowExpandingFeature,
  rowSortingFeature,
  tableFeatures,
  useTable,
} from "@tanstack/react-table";
import { ArrowDown, ArrowUp, ChevronRight, ChevronsUpDown, TriangleAlert } from "lucide-react";
import { Fragment, useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import { z } from "zod";
import {
  describeFourEyesConflicts,
  formatRemaining,
  useDecideSnapshot,
  useIngest,
  useMarketplaces,
  useProvenance,
  useRegisterMarketplace,
  useSnapshotReleaseAge,
  useSnapshotFourEyes,
  useSnapshotVetting,
  type MarketplaceView,
  type Snapshot,
} from "@/api/queries";
import { Timestamp } from "@/components/timestamp";
import { OutcomeBadge, VettingReport } from "@/components/vetting-report";
import { GATEWAY_NAME, GATEWAY_NAME_HINT, normalizeCloneUrl } from "@/lib/form-rules";
import { SnapshotStateBadge } from "@/components/snapshot-state";
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
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

// v9 requires the row-model factories and their feature flags to be declared up front, in a
// `features` object shared by the table's types and its runtime config.
const marketplacesTableFeatures = tableFeatures({
  columnVisibilityFeature,
  rowSortingFeature,
  rowExpandingFeature,
  sortedRowModel: createSortedRowModel(),
  expandedRowModel: createExpandedRowModel(),
});

const registerSchema = z.object({
  name: z
    .string()
    .regex(GATEWAY_NAME, "lowercase letters, digits, - and _; must not start with - or _"),
  // Scheme policy is enforced server-side (GW_0016, configurable allowlist);
  // the client only requires a well-formed absolute URL.
  url: z.url({ error: "must be a valid URL" }),
});

type RegisterForm = z.infer<typeof registerSchema>;

function RegisterMarketplaceDialog({ existing }: { existing: MarketplaceView[] }) {
  const [open, setOpen] = useState(false);
  const [acknowledgedDuplicate, setAcknowledgedDuplicate] = useState(false);
  const register = useRegisterMarketplace();
  // onChange validation is what lets Register stay disabled until both fields would be
  // accepted by the server, rather than failing on press.
  const form = useForm<RegisterForm>({
    resolver: zodResolver(registerSchema),
    defaultValues: { name: "", url: "" },
    mode: "onChange",
  });
  const urlValue = form.watch("url");
  // The gateway does not reject a repeated upstream — the same URL under two names is a
  // legitimate test setup — so this is a warning, not a block. When one is detected the user
  // must tick "register anyway" to proceed, which turns a silent collision into a deliberate one.
  const normalized = normalizeCloneUrl(urlValue ?? "");
  const duplicates =
    normalized === null
      ? []
      : existing.filter((m) => normalizeCloneUrl(m.url ?? "") === normalized);
  const hasDuplicate = duplicates.length > 0;
  const canRegister =
    form.formState.isValid && !register.isPending && (!hasDuplicate || acknowledgedDuplicate);

  const onSubmit = form.handleSubmit((values) => {
    register.mutate(values, {
      onSuccess: () => {
        toast.success(`Marketplace '${values.name}' registered`);
        form.reset();
        setAcknowledgedDuplicate(false);
        setOpen(false);
      },
      onError: (error) => toast.error(error.message),
    });
  });

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) setAcknowledgedDuplicate(false);
      }}
    >
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
          {hasDuplicate ? (
            <div
              role="alert"
              className="space-y-2 rounded-md border border-l-2 border-l-destructive bg-destructive/5 p-3"
            >
              <div className="flex items-start gap-2">
                <TriangleAlert className="mt-0.5 size-4 shrink-0 text-destructive" aria-hidden />
                <p className="text-sm">
                  This URL is already registered as{" "}
                  <span className="font-medium">
                    {duplicates.map((m) => m.name).join(", ")}
                  </span>
                  . Registering it again is allowed — the same upstream can be tracked under more
                  than one name — but it is usually a mistake.
                </p>
              </div>
              <label className="flex items-center gap-2 text-sm font-medium">
                <Checkbox
                  checked={acknowledgedDuplicate}
                  onCheckedChange={(value) => setAcknowledgedDuplicate(value === true)}
                />
                Register anyway
              </label>
            </div>
          ) : null}
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
 * Separation of duties is the third reason, and the only one the reviewer cannot resolve by doing
 * something to the snapshot: what disqualifies them is what they already did to it. Under warn —
 * the default — it says so and lets them through, because a single-administrator deployment has
 * nobody else to ask; under enforce it shuts the button and names the person who has to press it
 * instead. The server enforces all three independently.
 *
 * @Requirements GW_0042, GW_0047, GW_0073, GW_0096, GW_0097
 */
function ApproveDialog({ snapshotId, onClose }: { snapshotId: number; onClose: () => void }) {
  const vetting = useSnapshotVetting(snapshotId);
  const releaseAge = useSnapshotReleaseAge(snapshotId);
  const fourEyes = useSnapshotFourEyes(snapshotId);
  const decide = useDecideSnapshot();
  const blocked = vetting.data?.outcome === "BLOCKED" || vetting.data?.outcome === undefined;
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
            disabled={decide.isPending || blocked || tooYoung || refused}
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

/** The snapshot a reviewer means by "the latest one": newest by ingestion time. */
function latestSnapshot(marketplace: MarketplaceView): Snapshot | undefined {
  const snapshots = marketplace.snapshots ?? [];
  if (snapshots.length === 0) return undefined;
  return [...snapshots].sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? ""))[0];
}

/** The forge label for the row: the detected forge, else the bare host of the clone URL. */
function forgeLabel(marketplace: MarketplaceView): string {
  if (marketplace.forge) return marketplace.forge;
  try {
    return marketplace.url ? new URL(marketplace.url).host : "—";
  } catch {
    return "—";
  }
}

/**
 * The snapshots of one marketplace, revealed when its row is expanded: the same review table
 * as before — commit, state, vetting outcome, and the Approve/Reject/Provenance actions — plus
 * the Ingest control that fetches a fresh snapshot of the upstream default branch.
 */
function MarketplaceSnapshots({ marketplace }: { marketplace: MarketplaceView }) {
  const ingest = useIngest();
  const [provenanceId, setProvenanceId] = useState<number | null>(null);
  const snapshots = marketplace.snapshots ?? [];
  return (
    <section
      aria-label={`Snapshots of ${marketplace.name}`}
      className="space-y-3 bg-muted/30 p-4"
    >
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-medium">Snapshots of {marketplace.name}</h3>
        <div className="flex items-center gap-2">
          <Link
            to={`/marketplaces/${marketplace.name}`}
            className={buttonVariants({ variant: "outline", size: "sm" })}
          >
            Open detail
          </Link>
          <Button
            size="sm"
            variant="outline"
            onClick={() =>
              ingest.mutate(marketplace.name ?? "", {
                onSuccess: (snapshot) =>
                  toast.success(`Snapshot ${snapshot.sha?.slice(0, 12)} is ${snapshot.state}`),
                onError: (error) => toast.error(error.message),
              })
            }
            disabled={ingest.isPending}
            aria-label={`Ingest ${marketplace.name}`}
          >
            {ingest.isPending ? "Ingesting…" : "Ingest"}
          </Button>
        </div>
      </div>
      {snapshots.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          No snapshots yet — ingest to fetch the upstream default branch.
        </p>
      ) : (
        <div className="overflow-x-auto rounded-md border bg-background">
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
                <SnapshotRow
                  key={snapshot.id}
                  snapshot={snapshot}
                  onProvenance={setProvenanceId}
                />
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {provenanceId !== null ? (
        <ProvenanceDialog snapshotId={provenanceId} onClose={() => setProvenanceId(null)} />
      ) : null}
    </section>
  );
}

/** A sortable column header; the arrow shows the current direction, if any. */
function MarketHeader({
  label,
  sorted,
  onToggle,
}: {
  label: string;
  sorted: false | "asc" | "desc";
  onToggle: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onToggle}
      className="-ml-1 inline-flex items-center gap-1 rounded px-1 py-0.5 hover:text-foreground"
    >
      {label}
      {sorted === "asc" ? (
        <ArrowUp className="size-3" aria-hidden />
      ) : sorted === "desc" ? (
        <ArrowDown className="size-3" aria-hidden />
      ) : (
        <ChevronsUpDown className="size-3 text-muted-foreground/50" aria-hidden />
      )}
    </button>
  );
}

/**
 * Marketplace administration: register, ingest, review snapshots, approve/reject. One compact,
 * sortable row per marketplace — name, forge, its latest snapshot's state and vetting outcome,
 * and when upstream last moved — that expands in place to the snapshot review table. The name
 * links to the marketplace's full detail page.
 *
 * @Requirements GW_0018
 */
export function MarketplacesPage() {
  const marketplaces = useMarketplaces();
  const [sorting, setSorting] = useState<SortingState>([{ id: "name", desc: false }]);
  const [expanded, setExpanded] = useState<ExpandedState>({});
  const data = useMemo(() => marketplaces.data ?? [], [marketplaces.data]);

  const columns = useMemo<ColumnDef<typeof marketplacesTableFeatures, MarketplaceView, unknown>[]>(
    () => [
      {
        id: "expander",
        header: () => null,
        cell: ({ row }) => (
          <button
            type="button"
            aria-label={row.getIsExpanded() ? `Collapse ${row.original.name}` : `Expand ${row.original.name}`}
            aria-expanded={row.getIsExpanded()}
            onClick={() => row.toggleExpanded()}
            className="flex size-6 items-center justify-center rounded hover:bg-muted"
          >
            <ChevronRight
              className={`size-4 transition-transform ${row.getIsExpanded() ? "rotate-90" : ""}`}
              aria-hidden
            />
          </button>
        ),
      },
      {
        id: "name",
        header: ({ column }) => (
          <MarketHeader label="Name" sorted={column.getIsSorted()} onToggle={() => column.toggleSorting()} />
        ),
        accessorFn: (row) => row.name ?? "",
        cell: ({ row }) => (
          <Link
            to={`/marketplaces/${row.original.name}`}
            className="font-medium text-primary hover:underline"
          >
            {row.original.name}
          </Link>
        ),
      },
      {
        id: "forge",
        header: "Source",
        accessorFn: (row) => forgeLabel(row),
        cell: ({ row }) => (
          <div className="max-w-xs">
            <div className="text-sm">{forgeLabel(row.original)}</div>
            <div className="truncate text-xs text-muted-foreground" title={row.original.url}>
              {row.original.url ?? "—"}
            </div>
          </div>
        ),
      },
      {
        id: "latest",
        header: "Latest snapshot",
        enableSorting: false,
        cell: ({ row }) => {
          const snapshot = latestSnapshot(row.original);
          if (!snapshot) {
            return <span className="text-xs text-muted-foreground">none yet</span>;
          }
          return (
            <div className="flex flex-wrap items-center gap-2">
              <SnapshotStateBadge state={snapshot.state} />
              <VettingOutcomeCell snapshotId={snapshot.id ?? 0} />
            </div>
          );
        },
      },
      {
        id: "upstream",
        header: ({ column }) => (
          <MarketHeader
            label="Upstream updated"
            sorted={column.getIsSorted()}
            onToggle={() => column.toggleSorting()}
          />
        ),
        accessorFn: (row) => row.upstreamUpdatedAt ?? "",
        cell: ({ row }) =>
          row.original.upstreamUpdatedAt ? (
            <span className="whitespace-nowrap text-xs text-muted-foreground">
              <Timestamp value={row.original.upstreamUpdatedAt} />
            </span>
          ) : (
            <span className="text-xs text-muted-foreground">—</span>
          ),
      },
      {
        id: "count",
        header: "Snapshots",
        enableSorting: false,
        cell: ({ row }) => (
          <Badge variant="outline">{(row.original.snapshots ?? []).length}</Badge>
        ),
      },
    ],
    [],
  );

  const table = useTable({
    features: marketplacesTableFeatures,
    data,
    columns,
    state: { sorting, expanded },
    getRowId: (row) => String(row.id),
    onSortingChange: setSorting,
    onExpandedChange: setExpanded,
    // Every row expands to its own snapshots table — there are no real sub-rows for the
    // expanded row model to detect, so every row must report itself as expandable.
    getRowCanExpand: () => true,
  });

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Marketplaces</h1>
          <p className="text-sm text-muted-foreground">
            Registered upstreams and their quarantined, held, and approved snapshots.
          </p>
        </div>
        <RegisterMarketplaceDialog existing={data} />
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
      {data.length > 0 ? (
        <div className="overflow-x-auto rounded-md border">
          <Table>
            <TableHeader>
              {table.getHeaderGroups().map((group) => (
                <TableRow key={group.id}>
                  {group.headers.map((header) => (
                    <TableHead key={header.id}>
                      {header.isPlaceholder
                        ? null
                        : flexRender(header.column.columnDef.header, header.getContext())}
                    </TableHead>
                  ))}
                </TableRow>
              ))}
            </TableHeader>
            <TableBody>
              {table.getRowModel().rows.map((row) => (
                <Fragment key={row.id}>
                  <TableRow
                    data-state={row.getIsExpanded() ? "expanded" : undefined}
                    className="cursor-pointer"
                    onClick={(event) => {
                      // A click on the name link or an action must not also toggle the row.
                      if ((event.target as HTMLElement).closest("a,button")) return;
                      row.toggleExpanded();
                    }}
                  >
                    {row.getVisibleCells().map((visibleCell) => (
                      <TableCell key={visibleCell.id}>
                        {flexRender(visibleCell.column.columnDef.cell, visibleCell.getContext())}
                      </TableCell>
                    ))}
                  </TableRow>
                  {row.getIsExpanded() ? (
                    <TableRow className="hover:bg-transparent">
                      <TableCell colSpan={row.getVisibleCells().length} className="p-0">
                        <MarketplaceSnapshots marketplace={row.original} />
                      </TableCell>
                    </TableRow>
                  ) : null}
                </Fragment>
              ))}
            </TableBody>
          </Table>
        </div>
      ) : null}
    </div>
  );
}
