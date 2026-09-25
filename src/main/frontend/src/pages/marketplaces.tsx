import { zodResolver } from "@hookform/resolvers/zod";
import {
  type ColumnDef,
  type SortingState,
  columnVisibilityFeature,
  createSortedRowModel,
  flexRender,
  rowSortingFeature,
  tableFeatures,
  useTable,
} from "@tanstack/react-table";
import { ArrowDown, ArrowUp, ChevronsUpDown, TriangleAlert } from "lucide-react";
import { useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import { z } from "zod";
import {
  useMarketplaces,
  useRegisterMarketplace,
  type MarketplaceView,
  type Snapshot,
} from "@/api/queries";
import { Timestamp } from "@/components/timestamp";
import { SnapshotVettingBadge } from "@/components/vetting-report";
import { isDecidable } from "@/lib/snapshot-roles";
import {
  MARKETPLACE_NAME,
  MARKETPLACE_NAME_ERROR,
  MARKETPLACE_NAME_HINT,
  normalizeCloneUrl,
} from "@/lib/form-rules";
import { SnapshotStateBadge } from "@/components/snapshot-state";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
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
  sortedRowModel: createSortedRowModel(),
});

const registerSchema = z.object({
  name: z.string().regex(MARKETPLACE_NAME, MARKETPLACE_NAME_ERROR),
  // Scheme policy is enforced server-side (GW_INGEST_0005, configurable allowlist);
  // the client only requires a well-formed absolute URL.
  url: z.url({ error: "must be a valid URL" }),
});

type RegisterForm = z.infer<typeof registerSchema>;

/**
 * Warns rather than blocks on a duplicate upstream URL, client-side against the marketplaces
 * already loaded here and again from the server's authoritative check on the response.
 *
 * @Requirements GW_INGEST_0029, GW_INGEST_0063, GW_INGEST_0064
 */
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
      onSuccess: (registered) => {
        toast.success(`Marketplace '${values.name}' registered`);
        // The client-side check above catches most collisions before submission, but only
        // against the marketplace list already loaded here — the server checks again,
        // authoritatively, against every marketplace, and this is what a caller that skips the
        // portal entirely (the estate reconciler, a direct API client) relies on to see it at
        // all. Surfacing it here too closes the gap where this page's own check missed a
        // registration that landed between the page loading and this submission.
        for (const warning of registered.warnings ?? []) {
          toast.warning(warning);
        }
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
              {MARKETPLACE_NAME_HINT}
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

/** The snapshot a reviewer means by "the latest one": newest by ingestion time. */
function latestSnapshot(marketplace: MarketplaceView): Snapshot | undefined {
  const snapshots = marketplace.snapshots ?? [];
  if (snapshots.length === 0) return undefined;
  return [...snapshots].sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? ""))[0];
}

function awaitingCount(marketplace: MarketplaceView): number {
  return (marketplace.snapshots ?? []).filter(isDecidable).length;
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
 * Marketplace index: register, and one sortable row per marketplace — name, forge, its latest
 * snapshot's state and vetting outcome, when upstream last moved, and how many snapshots await a
 * decision. No decision is offered here: approve and reject live on the marketplace's review,
 * beside the evidence they rest on (GW_INGEST_0037).
 *
 * @Requirements GW_INGEST_0007, GW_INGEST_0037
 */
export function MarketplacesPage() {
  const marketplaces = useMarketplaces();
  const [sorting, setSorting] = useState<SortingState>([{ id: "name", desc: false }]);
  const data = useMemo(() => marketplaces.data ?? [], [marketplaces.data]);

  const columns = useMemo<ColumnDef<typeof marketplacesTableFeatures, MarketplaceView, unknown>[]>(
    () => [
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
              <SnapshotVettingBadge snapshotId={snapshot.id ?? 0} />
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
      {
        id: "awaiting",
        header: ({ column }) => (
          <MarketHeader label="Awaiting" sorted={column.getIsSorted()} onToggle={() => column.toggleSorting()} />
        ),
        accessorFn: (row) => awaitingCount(row),
        cell: ({ row }) => {
          const count = awaitingCount(row.original);
          return count === 0 ? (
            <span className="text-xs text-muted-foreground">—</span>
          ) : (
            <Link
              to={`/marketplaces/${row.original.name}`}
              aria-label={`${count} awaiting a decision in ${row.original.name}`}
              className="font-medium text-primary hover:underline"
            >
              {count}
            </Link>
          );
        },
      },
    ],
    [],
  );

  const table = useTable({
    features: marketplacesTableFeatures,
    data,
    columns,
    state: { sorting },
    getRowId: (row) => String(row.id),
    onSortingChange: setSorting,
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
                <TableRow key={row.id}>
                  {row.getVisibleCells().map((visibleCell) => (
                    <TableCell key={visibleCell.id}>
                      {flexRender(visibleCell.column.columnDef.cell, visibleCell.getContext())}
                    </TableCell>
                  ))}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      ) : null}
    </div>
  );
}
