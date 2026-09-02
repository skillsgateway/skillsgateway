import { CircleAlert, CircleCheck, ShieldAlert, TriangleAlert } from "lucide-react";
import { auditStatus, type AuditStatus } from "@/lib/audit-status";
import { Badge } from "@/components/ui/badge";

/**
 * The colour treatment for one ledger row, reusing the portal's existing verdict language:
 * the purple accent (primary) for a clean act, muted for a warn, and destructive red for a
 * refusal or violation — the same red the marketplace card paints, which is the point of #221.
 * The theme carries no green token, so "clear" is the accent, not a new colour.
 *
 * @Requirements GW_0030
 */
const LABEL: Record<AuditStatus, string> = {
  clear: "clear",
  warn: "warn",
  blocked: "blocked",
  neutral: "",
};

function icon(status: AuditStatus) {
  switch (status) {
    case "clear":
      return <CircleCheck className="size-3.5 text-primary" aria-hidden />;
    case "warn":
      return <TriangleAlert className="size-3.5 text-muted-foreground" aria-hidden />;
    case "blocked":
      return <ShieldAlert className="size-3.5 text-destructive" aria-hidden />;
    default:
      return <CircleAlert className="size-3.5 text-muted-foreground/50" aria-hidden />;
  }
}

/** A badge for a row that carries a verdict; nothing at all for a neutral row. */
export function AuditStatusBadge({ status }: { status: AuditStatus }) {
  if (status === "neutral") return null;
  const variant = status === "blocked" ? "destructive" : status === "clear" ? "default" : "secondary";
  return (
    <Badge variant={variant} className="gap-1">
      {icon(status)}
      {LABEL[status]}
    </Badge>
  );
}

/**
 * The row-level tint for a ledger row. A blocked row gets a faint destructive wash and a left
 * accent so it is findable in a long scroll; a clear/warn row stays quiet — the badge carries it.
 */
export function auditRowClass(row: Record<string, unknown>): string | undefined {
  const status = auditStatus(row);
  if (status === "blocked") {
    return "bg-destructive/5 border-l-2 border-l-destructive";
  }
  return undefined;
}
