/**
 * Client-side mirrors of the gateway's own request validation.
 *
 * Every rule here exists to keep a request the server would reject from being sent at
 * all — so a submit control can be disabled with a stated reason instead of failing on
 * press. The server stays authoritative: these are mirrors, never the check itself.
 *
 * Each export names the server code it mirrors. When that code changes, this changes
 * with it; a rule that is stricter than the server's silently forbids something the
 * gateway allows, and a rule that is looser puts the defect back.
 *
 * What deliberately is *not* mirrored here: rules the client cannot know. The URL
 * scheme allowlist is operator configuration (`skills-gateway.allowed-url-schemes`,
 * default http/https) and the webhook event registry is a server-side constant, so the
 * client validates the shape and lets the server own the policy.
 */

/**
 * Gateway-local resource names: marketplaces, webhook subscribers and audit sinks all
 * share one pattern. Mirrors `AdminController.MARKETPLACE_NAME`,
 * `WebhookController.SUBSCRIBER_NAME` and `AuditController.SINK_NAME`, each of which
 * rejects a non-matching name with 422.
 */
export const GATEWAY_NAME = /^[a-z0-9][a-z0-9_-]*$/;

export const GATEWAY_NAME_HINT =
  "Lowercase letters, digits, - and _; must start with a letter or digit.";

/** True when `value` is a name the server would accept. Whitespace is never a name. */
export function isValidGatewayName(value: string): boolean {
  return GATEWAY_NAME.test(value.trim());
}

/**
 * True when `value` parses as an absolute URL with a scheme.
 *
 * The server (`requireAllowlistedScheme`, identical in the admin, webhook and audit
 * controllers) parses the URL and rejects anything whose scheme is absent, unparseable
 * or off the configured allowlist. The client can only mirror the first two: the
 * allowlist is configuration, so a scheme this returns true for may still be refused —
 * with the server's own ProblemDetail message in a toast.
 */
export function isAbsoluteUrl(value: string): boolean {
  const trimmed = value.trim();
  if (trimmed.length === 0) return false;
  try {
    return new URL(trimmed).protocol.length > 1;
  } catch {
    return false;
  }
}

/**
 * A clone URL reduced to the shape two registrations would collide on: lowercased scheme and
 * host, no trailing slash, and the `.git` suffix dropped so `…/m` and `…/m.git` compare equal.
 *
 * This is a client-side aid only — it powers the duplicate-URL *warning* on the register form,
 * not a block. Registering the same upstream under two names is legitimate (it is how one tests
 * a marketplace), so the server does not reject it; the portal only surfaces it so the collision
 * is a deliberate choice rather than a silent one.
 */
export function normalizeCloneUrl(value: string): string | null {
  const trimmed = value.trim();
  if (trimmed.length === 0) return null;
  try {
    const url = new URL(trimmed);
    const path = url.pathname.replace(/\.git$/i, "").replace(/\/+$/, "");
    return `${url.protocol.toLowerCase()}//${url.host.toLowerCase()}${path}`;
  } catch {
    return null;
  }
}
