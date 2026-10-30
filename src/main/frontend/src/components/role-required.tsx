import { ShieldAlert } from "lucide-react";
import type { ReactNode } from "react";
import { useAccess } from "@/api/queries";
import { holds, roleName, type RequiredRole } from "@/lib/access";
import { Card, CardContent } from "@/components/ui/card";

/**
 * The one form a refusal by role takes on every page and section: what is missing, and why the
 * surface is behind it. It replaces the server's refusal text, an empty result and any count or
 * download that the refused read would have filled.
 *
 * @Requirements GW_AUTH_0065
 */
export function RoleRequired({
  role,
  title,
  description,
  children,
}: {
  role: RequiredRole;
  /** The page heading; omitted for a section inside a page that already has one. */
  title?: string;
  description?: string;
  /** What is behind the page and what the reader can do instead. */
  children?: ReactNode;
}) {
  return (
    <div className="space-y-4">
      {title ? (
        <div className="space-y-1">
          <h1 className="text-2xl font-semibold">{title}</h1>
          {description ? <p className="text-sm text-muted-foreground">{description}</p> : null}
        </div>
      ) : null}
      <Card>
        <CardContent className="flex items-start gap-3 py-6">
          <ShieldAlert className="mt-0.5 size-5 shrink-0 text-muted-foreground" aria-hidden />
          <div className="min-w-0 space-y-1">
            <p role="alert" className="text-sm font-medium break-words">
              This {title ? "page" : "section"} needs {roleName(role)}.
            </p>
            <p className="text-sm text-muted-foreground">
              {children ?? "Ask an administrator for the role."}
            </p>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

/**
 * Renders its children only for a session holding the role, and {@link RoleRequired} otherwise —
 * so the reads behind the children are never made for a session the server would refuse.
 */
export function RoleGate({
  role,
  title,
  description,
  explanation,
  children,
}: {
  role: RequiredRole;
  title?: string;
  description?: string;
  explanation?: ReactNode;
  children: ReactNode;
}) {
  const access = useAccess();
  if (access.isLoading) return <p className="text-sm text-muted-foreground">Loading…</p>;
  // A session that could not be read cannot be judged; the server still refuses what it must.
  if (access.isError || holds(access, role)) return <>{children}</>;
  return (
    <RoleRequired role={role} title={title} description={description}>
      {explanation}
    </RoleRequired>
  );
}
