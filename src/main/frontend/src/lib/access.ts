import type { EffectiveRole } from "@/api/queries";

/** What a session may do, as the server decides it. */
export type Access = {
  isAdmin: boolean;
  /** The ledger, its export and the operational listings. */
  isAuditor: boolean;
  /** Ingest, decide, waive and re-vet on this marketplace. */
  approves: (marketplace: string) => boolean;
};

/**
 * The client's copy of `RoleService`'s checks: `requireAdmin`, `requireAuditor` and
 * `requireApprover`. Kept in lock-step with them; a drift shows a control the server refuses (and
 * the refusal re-reads the session) or hides one it would allow — never more access.
 *
 * @Requirements GW_AUTH_0064
 */
export function accessOf(roles: readonly EffectiveRole[] | undefined): Access {
  const held = roles ?? [];
  const isAdmin = held.some((role) => role.role === "admin");
  const isAuditor = isAdmin || held.some((role) => role.role === "auditor");
  return {
    isAdmin,
    isAuditor,
    approves: (marketplace) =>
      isAdmin ||
      held.some((role) => role.role === "approver" && role.marketplace === marketplace),
  };
}

/** A role the server checks for: one of the global roles, or approval of one marketplace. */
export type RequiredRole = "admin" | "auditor" | { approverOf: string };

export function roleName(role: RequiredRole): string {
  if (role === "admin") return "the administrative role";
  if (role === "auditor") return "the auditor role";
  return `the approver role for ${role.approverOf}`;
}

export function holds(access: Access, role: RequiredRole): boolean {
  if (role === "admin") return access.isAdmin;
  if (role === "auditor") return access.isAuditor;
  return access.approves(role.approverOf);
}
