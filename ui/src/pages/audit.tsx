import { useAudit } from "@/api/queries";
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

/**
 * Append-only fetch ledger: every facade fetch with source, identity, repo, ref, and commit.
 *
 * @Requirements GW_0018
 */
export function AuditPage() {
  const audit = useAudit();
  const rows = audit.data ?? [];
  const columns = rows.length > 0 ? Object.keys(rows[0] ?? {}) : [];
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Audit log</h1>
        <p className="text-sm text-muted-foreground">Append-only ledger of every facade fetch.</p>
      </div>
      {audit.isLoading ? <p>Loading…</p> : null}
      {audit.isError ? (
        <p role="alert" className="text-sm text-destructive">
          {audit.error.message}
        </p>
      ) : null}
      {rows.length === 0 && !audit.isLoading ? (
        <p className="text-sm text-muted-foreground">No fetches recorded yet.</p>
      ) : null}
      {rows.length > 0 ? (
        <Table>
          <TableHeader>
            <TableRow>
              {columns.map((column) => (
                <TableHead key={column}>{column}</TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((row, index) => (
              <TableRow key={index}>
                {columns.map((column) => (
                  <TableCell key={column} className="font-mono text-xs">
                    {cell(row[column])}
                  </TableCell>
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      ) : null}
    </div>
  );
}
