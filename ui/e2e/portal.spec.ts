import { expect, test, type Page } from "@playwright/test";

/**
 * Real-browser acceptance: unmodified gateway + PostgreSQL + mock OIDC IdP
 * (compose.e2e.yaml), driven through the actual login redirect. Assertions use
 * role/name queries (accessibility tree) per ADR 0003.
 */

async function login(page: Page, username: string) {
  await page.goto("/");
  // The gateway redirects unauthenticated browsers to the IdP (GW_0011).
  await page.waitForURL(/9090/);
  await page.getByPlaceholder(/enter any user/i).fill(username);
  await page.getByRole("button", { name: /sign.?in/i }).click();
  await page.waitForURL(/marketplaces/);
}

function uniqueName(prefix: string) {
  return `${prefix}${Date.now().toString(36)}`;
}

/**
 * @SVCs SVC_GW_0018
 */
test("admin_registers_ingests_and_approves_a_marketplace_in_the_portal", async ({ page }) => {
  await login(page, "alice");
  const name = uniqueName("corp");

  await page.getByRole("button", { name: "Register marketplace" }).click();
  await page.getByLabel("Name").fill(name);
  await page.getByLabel("Clone URL").fill(process.env.E2E_UPSTREAM_URL ?? "file:///tmp/e2e-upstream");
  await page.getByRole("button", { name: "Register", exact: true }).click();
  await expect(page.getByText(`Marketplace '${name}' registered`)).toBeVisible();

  await page.getByRole("button", { name: `Ingest ${name}` }).click();
  await expect(page.getByText("held", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: /Approve snapshot \d+/ }).click();
  await expect(page.getByText("approved", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: /Provenance of snapshot \d+/ }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByText("Decided by")).toBeVisible();
  await expect(dialog.getByText("alice")).toBeVisible();
});

/**
 * @SVCs SVC_GW_0019
 */
test("token_cleartext_is_shown_once_and_revocation_marks_it_revoked", async ({ page }) => {
  await login(page, "alice");
  await page.getByRole("link", { name: "Access tokens" }).click();

  const tokenName = uniqueName("token");
  await page.getByLabel("Token name").fill(tokenName);
  await page.getByRole("button", { name: "Create token" }).click();

  const cleartext = page.getByTestId("token-cleartext");
  await expect(cleartext).toBeVisible();
  const value = await cleartext.textContent();
  expect(value).toBeTruthy();

  await page.getByRole("button", { name: "Done" }).click();
  // Show-once: the cleartext is gone and never rendered in the token list.
  await expect(page.getByTestId("token-cleartext")).toHaveCount(0);
  await expect(page.getByText(value ?? "__never__")).toHaveCount(0);

  const row = page.getByRole("row", { name: new RegExp(tokenName) });
  await row.getByRole("button", { name: `Revoke token ${tokenName}` }).click();
  await expect(row.getByText("revoked")).toBeVisible();
});
