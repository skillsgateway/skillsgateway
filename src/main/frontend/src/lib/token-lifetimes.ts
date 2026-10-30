/**
 * The lifetimes offered, and the one chosen by default — by the tokens page and the client set-up
 * wizard alike, so the two cannot drift (GW_AUTH_0070).
 *
 * Bounded by default rather than permanent: a credential minted to get a laptop working is the
 * one most likely to be forgotten, and a forgotten credential that never expires is a standing
 * grant nobody decided to make. Thirty days is long enough not to be an interruption.
 *
 * The server owns the actual ceiling (`skills-gateway.tokens.max-ttl`) and it is not exposed to
 * the browser, so this list cannot mirror it exactly — the one case design-conventions allows a
 * client rule to be looser. A deployment with a shorter cap refuses the longer options with a
 * 422, and the error is shown; nothing here raises or lowers any policy maximum.
 */
export const LIFETIMES = [
  { value: "P7D", label: "7 days", days: 7 },
  { value: "P30D", label: "30 days", days: 30 },
  { value: "P90D", label: "90 days", days: 90 },
  { value: "none", label: "No expiry", days: null },
] as const;

export type Lifetime = (typeof LIFETIMES)[number]["value"];

export const DEFAULT_LIFETIME: Lifetime = "P30D";

export function expiryFor(value: Lifetime): string | undefined {
  const chosen = LIFETIMES.find((lifetime) => lifetime.value === value);
  if (!chosen || chosen.days === null) return undefined;
  const at = new Date(Date.now() + chosen.days * 24 * 60 * 60 * 1000);
  return at.toISOString();
}
