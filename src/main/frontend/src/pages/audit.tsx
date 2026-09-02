import {
  type ColumnDef,
  type ColumnFiltersState,
  type SortingState,
  flexRender,
  getCoreRowModel,
  getFilteredRowModel,
  getPaginationRowModel,
  getSortedRowModel,
  useReactTable,
} from "@tanstack/react-table";
import { ArrowDown, ArrowUp, Check, ChevronsUpDown, Copy, Download } from "lucide-react";
import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import {
  AUDIT_EXPORT_URL,
  useAudit,
  useAuditSinks,
  useCreateAuditSink,
  useDeleteAuditSink,
  useResetAuditSinkCursor,
  type CreatedSink,
} from "@/api/queries";
import { AuditStatusBadge, auditRowClass } from "@/components/audit-status";
import { Timestamp } from "@/components/timestamp";
import { auditStatus } from "@/lib/audit-status";
import { GATEWAY_NAME_HINT, isAbsoluteUrl, isValidGatewayName } from "@/lib/form-rules";
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
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
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

function cell(value: unknown): string {
  if (value === null || value === undefined) {
    return "—";
  }
  return String(value);
}

type AuditRow = Record<string, unknown>;

/** The empty/"—" marketplace marker the ledger writes for actions not tied to a marketplace. */
const NO_MARKETPLACE = "-";

/** A sortable header button; the arrow states which way the column is ordered, if at all. */
function SortHeader({
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
 * The in-portal ledger view: the same append-only entries, but legible. Every row carries a
 * status derived from its event and detail (a blocked verdict reads red, matching the
 * marketplace card — #221/#224), the marketplace column links to that marketplace's detail
 * page, and the table sorts and filters per column. It paginates client-side over the JSON
 * ledger; the NDJSON export above is the path for a full, cursor-resumable pull.
 *
 * @Requirements GW_0022, GW_0030
 */
function LedgerTable({ rows }: { rows: AuditRow[] }) {
  const [sorting, setSorting] = useState<SortingState>([{ id: "id", desc: true }]);
  const [columnFilters, setColumnFilters] = useState<ColumnFiltersState>([]);

  const columns = useMemo<ColumnDef<AuditRow>[]>(
    () => [
      {
        id: "status",
        header: "Status",
        accessorFn: (row) => auditStatus(row),
        enableColumnFilter: false,
        cell: ({ row }) => <AuditStatusBadge status={auditStatus(row.original)} />,
      },
      {
        id: "ts",
        header: ({ column }) => (
          <SortHeader
            label="When"
            sorted={column.getIsSorted()}
            onToggle={() => column.toggleSorting()}
          />
        ),
        accessorFn: (row) => (typeof row.ts === "string" ? row.ts : ""),
        enableColumnFilter: false,
        cell: ({ row }) => (
          <span className="whitespace-nowrap text-xs text-muted-foreground">
            <Timestamp value={row.original.ts as string | undefined} />
          </span>
        ),
      },
      {
        id: "event",
        header: ({ column }) => (
          <SortHeader
            label="Event"
            sorted={column.getIsSorted()}
            onToggle={() => column.toggleSorting()}
          />
        ),
        accessorFn: (row) => cell(row.event),
        filterFn: "includesString",
        cell: ({ getValue }) => <span className="font-mono text-xs">{getValue<string>()}</span>,
      },
      {
        id: "principal",
        header: ({ column }) => (
          <SortHeader
            label="Principal"
            sorted={column.getIsSorted()}
            onToggle={() => column.toggleSorting()}
          />
        ),
        accessorFn: (row) => cell(row.principal),
        filterFn: "includesString",
        cell: ({ getValue }) => <span className="text-xs">{getValue<string>()}</span>,
      },
      {
        id: "marketplace",
        header: ({ column }) => (
          <SortHeader
            label="Marketplace"
            sorted={column.getIsSorted()}
            onToggle={() => column.toggleSorting()}
          />
        ),
        accessorFn: (row) => cell(row.marketplace),
        filterFn: "includesString",
        cell: ({ row }) => {
          const value = cell(row.original.marketplace);
          if (value === NO_MARKETPLACE || value === "—") {
            return <span className="text-xs text-muted-foreground">—</span>;
          }
          // Links to the marketplace detail even if it was later removed; that page states the
          // "not found" case rather than leaving the ledger a dead end (#221/#224).
          return (
            <Link
              to={`/marketplaces/${encodeURIComponent(value)}`}
              className="text-xs font-medium text-primary hover:underline"
            >
              {value}
            </Link>
          );
        },
      },
      {
        id: "sha",
        header: "Commit",
        accessorFn: (row) => cell(row.sha),
        filterFn: "includesString",
        cell: ({ getValue }) => {
          const value = getValue<string>();
          return (
            <span className="font-mono text-xs text-muted-foreground">
              {value && value !== "—" ? value.slice(0, 12) : "—"}
            </span>
          );
        },
      },
      {
        id: "detail",
        header: "Detail",
        accessorFn: (row) => cell(row.detail),
        enableColumnFilter: false,
        enableSorting: false,
        cell: ({ getValue }) => (
          <span className="text-xs text-muted-foreground">{getValue<string>()}</span>
        ),
      },
    ],
    [],
  );

  const table = useReactTable({
    data: rows,
    columns,
    state: { sorting, columnFilters },
    onSortingChange: setSorting,
    onColumnFiltersChange: setColumnFilters,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
    getPaginationRowModel: getPaginationRowModel(),
    initialState: { pagination: { pageSize: 25 } },
  });

  const filterable: { id: string; label: string }[] = [
    { id: "event", label: "event" },
    { id: "principal", label: "principal" },
    { id: "marketplace", label: "marketplace" },
    { id: "sha", label: "sha" },
  ];

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-2">
        {filterable.map((f) => {
          const column = table.getColumn(f.id);
          return (
            <Input
              key={f.id}
              className="h-8 w-40 text-xs"
              aria-label={`Filter by ${f.label}`}
              placeholder={`Filter ${f.label}…`}
              value={(column?.getFilterValue() as string) ?? ""}
              onChange={(event) => column?.setFilterValue(event.target.value)}
            />
          );
        })}
      </div>
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
            {table.getRowModel().rows.length === 0 ? (
              <TableRow>
                <TableCell colSpan={columns.length} className="text-sm text-muted-foreground">
                  No rows match the current filters.
                </TableCell>
              </TableRow>
            ) : (
              table.getRowModel().rows.map((row) => (
                <TableRow key={row.id} className={auditRowClass(row.original)}>
                  {row.getVisibleCells().map((cellCtx) => (
                    <TableCell key={cellCtx.id}>
                      {flexRender(cellCtx.column.columnDef.cell, cellCtx.getContext())}
                    </TableCell>
                  ))}
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </div>
      <div className="flex items-center justify-between text-xs text-muted-foreground">
        <span>
          {table.getFilteredRowModel().rows.length} of {rows.length} entries
        </span>
        <div className="flex items-center gap-2">
          <Button
            size="sm"
            variant="outline"
            aria-label="Previous page"
            disabled={!table.getCanPreviousPage()}
            onClick={() => table.previousPage()}
          >
            Previous
          </Button>
          <span>
            Page {table.getState().pagination.pageIndex + 1} of {Math.max(1, table.getPageCount())}
          </span>
          <Button
            size="sm"
            variant="outline"
            aria-label="Next page"
            disabled={!table.getCanNextPage()}
            onClick={() => table.nextPage()}
          >
            Next
          </Button>
        </div>
      </div>
    </div>
  );
}

export function SinkSecretDialog({
  created,
  onClose,
}: {
  created: CreatedSink;
  onClose: () => void;
}) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(created.secret ?? "");
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      toast.error("Could not access the clipboard");
    }
  };
  return (
    <Dialog open onOpenChange={(open) => (open ? undefined : onClose())}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Sink '{created.name}' created</DialogTitle>
          <DialogDescription>
            This signing secret is shown exactly once — copy it now. Every exported batch is signed
            with it, HMAC-SHA256 over the request body.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-2">
          <Label htmlFor="sink-secret-box">Signing secret</Label>
          <div id="sink-secret-box" className="flex items-center gap-2 rounded-md border bg-muted p-3">
            <code data-testid="sink-secret" className="flex-1 break-all text-sm">
              {created.secret}
            </code>
            <Button
              variant="outline"
              size="icon"
              aria-label="Copy signing secret to clipboard"
              onClick={() => void copy()}
            >
              {copied ? <Check className="size-4" aria-hidden /> : <Copy className="size-4" aria-hidden />}
            </Button>
          </div>
        </div>
        <DialogFooter>
          <Button onClick={onClose}>Done</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Append-only ledger: every facade fetch and administrative action, plus the compliance
 * export surface — the NDJSON download and the sinks that stream the ledger onwards, each
 * with its position in it.
 *
 * @Requirements GW_0018, GW_0030
 */
export function AuditPage() {
  const audit = useAudit();
  const sinks = useAuditSinks();
  const create = useCreateAuditSink();
  const remove = useDeleteAuditSink();
  const resetCursor = useResetAuditSinkCursor();
  const [name, setName] = useState("");
  const [url, setUrl] = useState("");
  const [created, setCreated] = useState<CreatedSink | null>(null);
  const rows = audit.data ?? [];

  // Mirrors AuditController.createSink: the name must match the gateway name pattern
  // (422) and the URL must parse with a scheme (400). The scheme allowlist itself is
  // operator configuration, so it stays server-side and surfaces as a toast.
  const trimmedName = name.trim();
  const trimmedUrl = url.trim();
  const canCreate =
    isValidGatewayName(trimmedName) && isAbsoluteUrl(trimmedUrl) && !create.isPending;

  const onCreate = (event: React.FormEvent) => {
    event.preventDefault();
    if (!canCreate) {
      return;
    }
    create.mutate(
      { name: trimmedName, url: trimmedUrl },
      {
        onSuccess: (sink) => {
          setCreated(sink);
          setName("");
          setUrl("");
        },
        onError: (error) => toast.error(error.message),
      },
    );
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Audit log</h1>
        <p className="text-sm text-muted-foreground">
          Append-only ledger of every facade fetch and administrative action, exportable to an
          external compliance system.
        </p>
      </div>

      <section className="space-y-3">
        <h2 className="text-lg font-semibold">Export</h2>
        <p className="text-sm text-muted-foreground">
          The stream is newline-delimited JSON in ledger order. A collector polls it with the cursor
          from the previous response; a sink is the same feed pushed instead of pulled.
        </p>
        <a
          href={AUDIT_EXPORT_URL}
          download="audit-ledger.ndjson"
          className={buttonVariants({ variant: "outline" })}
        >
          <Download className="size-4" aria-hidden />
          Download ledger (NDJSON)
        </a>
      </section>

      <section className="space-y-3">
        <h2 className="text-lg font-semibold">Export sinks</h2>
        <form onSubmit={onCreate} className="space-y-2">
          <div className="flex flex-wrap items-end gap-3">
            <div className="space-y-2">
              <Label htmlFor="sink-name">Sink name</Label>
              <Input
                id="sink-name"
                value={name}
                onChange={(event) => setName(event.target.value)}
                autoComplete="off"
                placeholder="siem"
                aria-describedby="sink-form-hint"
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="sink-url">Target URL</Label>
              <Input
                id="sink-url"
                value={url}
                onChange={(event) => setUrl(event.target.value)}
                autoComplete="off"
                placeholder="https://siem.example.com/ingest/skills-gateway"
                aria-describedby="sink-form-hint"
              />
            </div>
            <Button type="submit" disabled={!canCreate}>
              {create.isPending ? "Adding…" : "Add sink"}
            </Button>
          </div>
          <p id="sink-form-hint" className="text-xs text-muted-foreground">
            A name and a target URL are required — Add sink enables once both are valid.{" "}
            {GATEWAY_NAME_HINT}
          </p>
        </form>
        {sinks.isLoading ? <p>Loading…</p> : null}
        {sinks.isError ? (
          <p role="alert" className="text-sm text-destructive">
            {sinks.error.message}
          </p>
        ) : null}
        {sinks.data?.length === 0 ? (
          <p className="text-sm text-muted-foreground">No export sinks yet.</p>
        ) : null}
        {sinks.data && sinks.data.length > 0 ? (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Target URL</TableHead>
                <TableHead>Position</TableHead>
                <TableHead>Behind</TableHead>
                <TableHead>Status</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {sinks.data.map((sink) => (
                <TableRow key={sink.id}>
                  <TableCell>{sink.name}</TableCell>
                  <TableCell className="break-all">{sink.url}</TableCell>
                  <TableCell className="font-mono text-xs">{sink.cursorPosition}</TableCell>
                  <TableCell>
                    <span className="rounded-md border bg-muted px-2 py-0.5 text-xs">
                      {sink.behind} entries
                    </span>
                  </TableCell>
                  <TableCell>
                    {sink.enabled ? <Badge>enabled</Badge> : <Badge variant="secondary">disabled</Badge>}
                  </TableCell>
                  <TableCell className="space-x-2 text-right">
                    <Button
                      size="sm"
                      variant="outline"
                      aria-label={`Replay sink ${sink.name} from the beginning`}
                      disabled={resetCursor.isPending}
                      onClick={() =>
                        resetCursor.mutate(
                          { id: sink.id ?? 0, after: 0 },
                          {
                            onSuccess: () => toast.success(`Sink '${sink.name}' will replay the ledger`),
                            onError: (error) => toast.error(error.message),
                          },
                        )
                      }
                    >
                      Replay
                    </Button>
                    <Button
                      size="sm"
                      variant="destructive"
                      aria-label={`Delete sink ${sink.name}`}
                      disabled={remove.isPending}
                      onClick={() =>
                        remove.mutate(sink.id ?? 0, {
                          onSuccess: () => toast.success(`Sink '${sink.name}' deleted`),
                          onError: (error) => toast.error(error.message),
                        })
                      }
                    >
                      Delete
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        ) : null}
      </section>

      <section className="space-y-3">
        <h2 className="text-lg font-semibold">Ledger</h2>
        <p className="text-sm text-muted-foreground">
          Every facade fetch and administrative action, in ledger order. A verdict row is
          coloured — a blocked snapshot reads red, the same as its marketplace — and each
          marketplace links to its detail page.
        </p>
        {audit.isLoading ? <p>Loading…</p> : null}
        {audit.isError ? (
          <p role="alert" className="text-sm text-destructive">
            {audit.error.message}
          </p>
        ) : null}
        {rows.length === 0 && !audit.isLoading ? (
          <p className="text-sm text-muted-foreground">No fetches recorded yet.</p>
        ) : null}
        {rows.length > 0 ? <LedgerTable rows={rows} /> : null}
      </section>

      {created ? <SinkSecretDialog created={created} onClose={() => setCreated(null)} /> : null}
    </div>
  );
}
