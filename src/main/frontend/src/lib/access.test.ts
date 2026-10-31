import { expect, test } from "vitest";
import { accessOf } from "./access";

/** Each persona, decided as RoleService decides it. */
test("access_mirrors_the_servers_role_checks", () => {
  const none = accessOf([]);
  expect([none.isAdmin, none.isAuditor, none.approves("a")]).toEqual([false, false, false]);
  expect(accessOf(undefined).isAuditor).toBe(false);

  const auditor = accessOf([{ role: "auditor", source: "grant" }]);
  expect([auditor.isAdmin, auditor.isAuditor, auditor.approves("a")]).toEqual([false, true, false]);

  const approver = accessOf([{ role: "approver", marketplace: "a", source: "grant" }]);
  expect([approver.isAdmin, approver.isAuditor]).toEqual([false, false]);
  expect(approver.approves("a")).toBe(true);
  // Exactly the name: no prefix, no case folding (GW_AUTH_0011).
  expect(approver.approves("b")).toBe(false);
  expect(approver.approves("A")).toBe(false);
  expect(approver.approves("a-b")).toBe(false);

  const admin = accessOf([{ role: "admin", source: "config" }]);
  expect([admin.isAdmin, admin.isAuditor, admin.approves("anything")]).toEqual([true, true, true]);
});
