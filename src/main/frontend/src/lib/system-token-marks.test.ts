import { expect, test } from "vitest";
import type { MachineCredentialView } from "@/api/queries";
import { marksOf, stateOf } from "./system-token-marks";

const NOW = Date.parse("2026-11-16T12:00:00Z");
const DAY = 86_400_000;
const iso = (offset: number) => new Date(NOW + offset).toISOString();

function token(overrides: Partial<MachineCredentialView>): MachineCredentialView {
  return { id: 1, name: "t", principal: "p", createdAt: iso(-30 * DAY), lastUsedAt: iso(-DAY), expiresAt: iso(100 * DAY), ...overrides };
}

/** @SVCs SVC_GW_AUTH_0090 */
test("the_expiry_mark_starts_at_fourteen_days_and_not_before", () => {
  expect(marksOf(token({ expiresAt: iso(13 * DAY) }), NOW).expiringSoon).toBe(true);
  expect(marksOf(token({ expiresAt: iso(14 * DAY) }), NOW).expiringSoon).toBe(true);
  expect(marksOf(token({ expiresAt: iso(15 * DAY) }), NOW).expiringSoon).toBe(false);
});

/** @SVCs SVC_GW_AUTH_0090 */
test("never_used_needs_a_day_of_age_so_a_new_credential_is_not_accused", () => {
  expect(marksOf(token({ lastUsedAt: undefined, createdAt: iso(-2 * DAY) }), NOW).neverUsed).toBe(true);
  expect(marksOf(token({ lastUsedAt: undefined, createdAt: iso(-23 * 3_600_000) }), NOW).neverUsed).toBe(false);
  expect(marksOf(token({ lastUsedAt: iso(-1000), createdAt: iso(-2 * DAY) }), NOW).neverUsed).toBe(false);
});

test("a_credential_with_no_expiry_says_so_and_is_never_called_expiring", () => {
  const marks = marksOf(token({ expiresAt: undefined }), NOW);
  expect(marks).toEqual({ expiringSoon: false, neverUsed: false, neverExpires: true });
});

test("a_revoked_or_expired_credential_carries_no_marks", () => {
  const revoked = token({ revokedAt: iso(-DAY), lastUsedAt: undefined, expiresAt: iso(DAY) });
  const expired = token({ expiresAt: iso(-DAY), lastUsedAt: undefined });
  for (const dead of [revoked, expired]) {
    expect(marksOf(dead, NOW)).toEqual({ expiringSoon: false, neverUsed: false, neverExpires: false });
  }
  expect(stateOf(revoked, NOW)).toBe("revoked");
  expect(stateOf(expired, NOW)).toBe("expired");
  expect(stateOf(token({}), NOW)).toBe("active");
});
