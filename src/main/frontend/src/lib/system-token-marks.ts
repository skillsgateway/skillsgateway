import type { MachineCredentialView } from "@/api/queries";

/** A credential that expires within this many days is called out (GW_AUTH_0074). */
export const EXPIRING_WITHIN_DAYS = 14;
/**
 * A credential this much older than now with no authentication ever is called out. A day, so one
 * minted a moment ago — which has had no chance to be used — is not accused of being forgotten.
 */
export const UNUSED_AFTER_HOURS = 24;

const DAY = 24 * 60 * 60 * 1000;
const HOUR = 60 * 60 * 1000;

export type SystemTokenState = "revoked" | "expired" | "active";

export function stateOf(token: MachineCredentialView, now: number = Date.now()): SystemTokenState {
  if (token.revokedAt) return "revoked";
  if (token.expiresAt && Date.parse(token.expiresAt) <= now) return "expired";
  return "active";
}

export type SystemTokenMarks = {
  /** Live, with an expiry inside {@link EXPIRING_WITHIN_DAYS}. */
  expiringSoon: boolean;
  /** Live, older than {@link UNUSED_AFTER_HOURS}, and never authenticated. */
  neverUsed: boolean;
  /** Live and without an expiry, which is a choice an administrator made explicitly. */
  neverExpires: boolean;
};

/**
 * What to say about a live credential beyond its dates. A revoked or expired one needs none: it
 * can no longer do anything, so neither an expiry warning nor an unused one is a call to act.
 *
 * @Requirements GW_AUTH_0074
 */
export function marksOf(token: MachineCredentialView, now: number = Date.now()): SystemTokenMarks {
  if (stateOf(token, now) !== "active") {
    return { expiringSoon: false, neverUsed: false, neverExpires: false };
  }
  const expires = token.expiresAt ? Date.parse(token.expiresAt) : null;
  const created = token.createdAt ? Date.parse(token.createdAt) : now;
  return {
    expiringSoon: expires !== null && expires - now <= EXPIRING_WITHIN_DAYS * DAY,
    neverUsed: !token.lastUsedAt && now - created > UNUSED_AFTER_HOURS * HOUR,
    neverExpires: expires === null,
  };
}
