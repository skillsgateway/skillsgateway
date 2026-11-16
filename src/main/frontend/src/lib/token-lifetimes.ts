/**
 * The lifetimes offered, and the one chosen by default — by the tokens page and the client set-up
 * wizard alike, so the two cannot drift (GW_AUTH_0070, GW_AUTH_0078).
 *
 * Bounded, with no way to choose "never": a credential minted to get a laptop working is the one
 * most likely to be forgotten, and a forgotten credential that never expires is a standing grant
 * nobody decided to make. Thirty days is long enough not to be an interruption and a year covers
 * long-running personal use.
 *
 * The server owns the actual ceiling (`skills-gateway.tokens.max-ttl`) and it is not exposed to
 * the browser, so this list cannot mirror it exactly — the one case design-conventions allows a
 * client rule to be looser. A deployment with a shorter cap refuses the longer options with a
 * 422, and the error is shown; nothing here raises or lowers any policy maximum.
 */
export const LIFETIMES = [
  { value: "P30D", label: "30 days", days: 30 },
  { value: "P90D", label: "90 days", days: 90 },
  { value: "P180D", label: "180 days", days: 180 },
  { value: "P365D", label: "1 year", days: 365 },
] as const;

export type Lifetime = (typeof LIFETIMES)[number]["value"];

export const DEFAULT_LIFETIME: Lifetime = "P30D";

/**
 * What a system access token may be given (GW_AUTH_0072, GW_AUTH_0077): the personal list plus
 * "never", which only an administrator can choose and which the API takes as an explicit
 * `neverExpires`, not as an omitted expiry. There is no default: the choice is made.
 */
export const SYSTEM_LIFETIMES = [
  ...LIFETIMES,
  { value: "never", label: "Never", days: null },
] as const;

export type SystemLifetime = (typeof SYSTEM_LIFETIMES)[number]["value"];

function isoAhead(days: number): string {
  return new Date(Date.now() + days * 24 * 60 * 60 * 1000).toISOString();
}

export function expiryFor(value: Lifetime): string | undefined {
  const chosen = LIFETIMES.find((lifetime) => lifetime.value === value);
  return chosen ? isoAhead(chosen.days) : undefined;
}

/** The lifetime fields of a system access token request: a date, or the explicit never. */
export function systemExpiryFor(
  value: SystemLifetime,
): { expiresAt: string } | { neverExpires: true } {
  const chosen = SYSTEM_LIFETIMES.find((lifetime) => lifetime.value === value);
  if (!chosen || chosen.days === null) return { neverExpires: true };
  return { expiresAt: isoAhead(chosen.days) };
}
