import {
  type ColumnDef,
  type ColumnFiltersState,
  type FilterFn,
  type SortingState,
  columnFilteringFeature,
  columnVisibilityFeature,
  createFilteredRowModel,
  createPaginatedRowModel,
  createSortedRowModel,
  flexRender,
  rowPaginationFeature,
  rowSortingFeature,
  tableFeatures,
  useTable,
} from "@tanstack/react-table";
import { ArrowDown, ArrowUp, ChevronsUpDown, Download } from "lucide-react";
import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import {
  AUDIT_EXPORT_URL,
  useAudit,
  useAuditSinks,
  useMarketplaces,
} from "@/api/queries";
import { AuditStatusBadge, auditRowClass } from "@/components/audit-status";
import { Timestamp } from "@/components/timestamp";
import { auditStatus } from "@/lib/audit-status";
import { Button, buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
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

// v9 requires the row-model factories and their feature flags to be declared up front, in a
// `features` object shared by the table's types (columns, filter fns) and its runtime config.
const ledgerTableFeatures = tableFeatures({
  columnFilteringFeature,
  columnVisibilityFeature,
  rowSortingFeature,
  rowPaginationFeature,
  filteredRowModel: createFilteredRowModel(),
  sortedRowModel: createSortedRowModel(),
  paginatedRowModel: createPaginatedRowModel(),
});

/** Case-insensitive substring match — the same behavior as v8's built-in "includesString". */
const includesString: FilterFn<typeof ledgerTableFeatures, AuditRow> = (
  row,
  columnId,
  filterValue,
) => {
  const value = String(row.getValue(columnId) ?? "").toLowerCase();
  return value.includes(String(filterValue ?? "").toLowerCase());
};

/** Distinct, sorted, presentable values for a filter's completion list — placeholders dropped. */
function distinctFacet(values: unknown[]): string[] {
  return Array.from(
    new Set(
      values
        .map((value) => cell(value))
        .filter((value) => value !== "—" && value !== NO_MARKETPLACE && value.trim() !== ""),
    ),
  ).sort((a, b) => a.localeCompare(b));
}

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
 * @Requirements GW_AUDIT_0002, GW_AUDIT_0006
 */
function LedgerTable({ rows }: { rows: AuditRow[] }) {
  // Newest first. The column id has to be one the table actually has: "id" named no column, so
  // TanStack silently ignored the whole sort and rendered the ledger oldest-first — which put an
  // administrator's change at the bottom, or on the last page, right after they made it.
  const [sorting, setSorting] = useState<SortingState>([{ id: "ts", desc: true }]);
  const [columnFilters, setColumnFilters] = useState<ColumnFiltersState>([]);

  // Completion options for each free-text filter. The marketplace column is sourced from the
  // authoritative marketplaces list so it completes beyond the loaded page; the rest are derived
  // from the rows currently in hand (the ledger is paginated client-side over /api/audit, so
  // event/principal/sha options only cover loaded rows). Filters stay free-text either way.
  const marketplaces = useMarketplaces();
  const facets = useMemo<Record<string, string[]>>(() => {
    const marketplaceNames = (marketplaces.data ?? [])
      .map((m) => m.name)
      .filter((name): name is string => Boolean(name));
    return {
      event: distinctFacet(rows.map((row) => row.event)),
      principal: distinctFacet(rows.map((row) => row.principal)),
      // Prefer the registry; union with any marketplace seen only in the loaded rows.
      marketplace: distinctFacet([...marketplaceNames, ...rows.map((row) => row.marketplace)]),
      sha: distinctFacet(rows.map((row) => row.sha)),
    };
  }, [rows, marketplaces.data]);

  const columns = useMemo<ColumnDef<typeof ledgerTableFeatures, AuditRow, unknown>[]>(
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
        filterFn: includesString,
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
        filterFn: includesString,
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
        filterFn: includesString,
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
        filterFn: includesString,
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

  const table = useTable({
    features: ledgerTableFeatures,
    data: rows,
    columns,
    state: { sorting, columnFilters },
    onSortingChange: setSorting,
    onColumnFiltersChange: setColumnFilters,
    initialState: { pagination: { pageIndex: 0, pageSize: 25 } },
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
          const options = facets[f.id] ?? [];
          return (
            // Wide enough for what actually goes in these boxes — a dotted event name such as
            // marketplace.snapshot.approved is the longest of them — and growing to share the
            // row, so nothing is read through a 160px window.
            <div key={f.id} className="min-w-0 flex-1 basis-56">
              <Input
                list={`facet-${f.id}`}
                className="h-8 w-full text-xs"
                aria-label={`Filter by ${f.label}`}
                placeholder={`Filter ${f.label}…`}
                value={(column?.getFilterValue() as string) ?? ""}
                onChange={(event) => column?.setFilterValue(event.target.value)}
              />
              <datalist id={`facet-${f.id}`}>
                {options.map((option) => (
                  <option key={option} value={option} />
                ))}
              </datalist>
            </div>
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
            Page {table.state.pagination.pageIndex + 1} of {Math.max(1, table.getPageCount())}
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

/**
 * Append-only ledger: every facade fetch and administrative action, with its NDJSON download
 * as the page's action. The sinks that push the same feed onwards are an integration, listed
 * beside the webhook subscribers.
 *
 * @Requirements GW_INGEST_0007, GW_AUDIT_0006
 */
export function AuditPage() {
  const audit = useAudit();
  const sinks = useAuditSinks();
  const rows = audit.data ?? [];
  const sinkCount = sinks.data?.length ?? 0;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-3">
        <div className="min-w-0 flex-1 basis-80 space-y-1">
          <h1 className="text-2xl font-semibold">Audit log</h1>
          <p className="text-sm text-muted-foreground">
            Append-only ledger of every facade fetch and administrative action, exportable to an
            external compliance system.
          </p>
        </div>
        <a
          href={AUDIT_EXPORT_URL}
          download="audit-ledger.ndjson"
          className={buttonVariants({ variant: "outline" })}
          aria-describedby="audit-export-hint"
        >
          <Download className="size-4" aria-hidden />
          Download ledger (NDJSON)
        </a>
      </div>
      <p id="audit-export-hint" className="text-xs text-muted-foreground">
        The download is newline-delimited JSON in ledger order.{" "}
        {sinks.data ? (
          <>
            {sinkCount === 0
              ? "No sink pushes it onwards"
              : `Pushed onwards to ${sinkCount} ${sinkCount === 1 ? "sink" : "sinks"}`}{" "}
            —{" "}
            <Link
              to="/integrations/sinks"
              className="font-medium text-primary underline-offset-4 hover:underline"
            >
              Audit sinks
            </Link>
            .
          </>
        ) : null}
      </p>

      <section className="space-y-3">
        <h2 className="text-lg font-semibold">Ledger</h2>
        <p className="text-sm text-muted-foreground">
          Every facade fetch and administrative action, newest first. A verdict row is
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
    </div>
  );
}
