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
  // Back on the portal: the sidebar navigation is the landmark.
  await expect(page.getByRole("navigation", { name: "Main" })).toBeVisible();
}

function uniqueName(prefix: string) {
  return `${prefix}${Date.now().toString(36)}`;
}

/**
 * @SVCs SVC_GW_0018
 */
test("admin_registers_ingests_and_approves_a_marketplace_in_the_portal", async ({ page }) => {
  await login(page, "alice");
  await page
    .getByRole("navigation", { name: "Main" })
    .getByRole("link", { name: "Marketplaces" })
    .click();
  const name = uniqueName("corp");

  await page.getByRole("button", { name: "Register marketplace" }).click();
  // An empty dialog cannot be submitted: Register enables once both fields are valid.
  await expect(page.getByRole("button", { name: "Register", exact: true })).toBeDisabled();
  await page.getByLabel("Name").fill(name);
  await page.getByLabel("Clone URL").fill(process.env.E2E_UPSTREAM_URL ?? "file:///tmp/e2e-upstream");
  await page.getByRole("button", { name: "Register", exact: true }).click();
  await expect(page.getByText(`Marketplace '${name}' registered`)).toBeVisible();

  await page.getByRole("button", { name: `Ingest ${name}` }).click();
  await expect(page.getByText("held", { exact: true })).toBeVisible();

  // Approval goes through the review dialog: the reviewer sees the verdicts first (GW_0042).
  await page.getByRole("button", { name: /Approve snapshot \d+/ }).click();
  await page.getByRole("button", { name: /Confirm approval of snapshot \d+/ }).click();
  await expect(page.getByText("approved", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: /Provenance of snapshot \d+/ }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByText("Decided by")).toBeVisible();
  await expect(dialog.getByText("alice")).toBeVisible();
  await page.keyboard.press("Escape");

  // Detail view: the snapshot's plugin/skill inventory (GW_0020).
  await page.getByRole("link", { name, exact: true }).click();
  await page.getByRole("button", { name: /Show contents of snapshot \d+/ }).click();
  await expect(page.getByText("hello", { exact: true }).first()).toBeVisible();
});

/**
 * @SVCs SVC_GW_0019
 */
test("token_cleartext_is_shown_once_and_revocation_marks_it_revoked", async ({ page }) => {
  await login(page, "alice");
  await page.getByRole("link", { name: "Access tokens" }).click();

  // The name is required: nothing can be submitted until one is entered.
  await expect(page.getByRole("button", { name: "Create token" })).toBeDisabled();

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

/**
 * @SVCs SVC_GW_0026
 */
test("webhooks_page_lists_subscribers_and_delivery_attempts", async ({ page }) => {
  await login(page, "alice");
  await page.getByRole("link", { name: "Webhooks" }).click();

  const subscriberName = uniqueName("hook");
  await expect(page.getByRole("button", { name: "Add subscriber" })).toBeDisabled();
  await page.getByLabel("Subscriber name").fill(subscriberName);
  // Nothing listens there: the delivery is still recorded, which is what this page shows.
  await page.getByLabel("Target URL").fill("http://127.0.0.1:9/hook");
  await page.getByRole("button", { name: "Add subscriber" }).click();
  await expect(page.getByTestId("webhook-secret")).toBeVisible();
  await page.getByRole("button", { name: "Done" }).click();

  const subscriberRow = page.getByRole("row", { name: new RegExp(subscriberName) });
  await expect(subscriberRow.getByText("all events")).toBeVisible();

  // A lifecycle event: register and ingest a marketplace, then come back.
  await page
    .getByRole("navigation", { name: "Main" })
    .getByRole("link", { name: "Marketplaces" })
    .click();
  const marketplaceName = uniqueName("hookcorp");
  await page.getByRole("button", { name: "Register marketplace" }).click();
  await page.getByLabel("Name").fill(marketplaceName);
  await page.getByLabel("Clone URL").fill(process.env.E2E_UPSTREAM_URL ?? "file:///tmp/e2e-upstream");
  await page.getByRole("button", { name: "Register", exact: true }).click();
  await page.getByRole("button", { name: `Ingest ${marketplaceName}` }).click();
  await expect(page.getByText("held", { exact: true })).toBeVisible();

  await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Webhooks" }).click();
  await expect(
    page.getByRole("row", { name: new RegExp(`snapshot\\.ingested ${subscriberName}`) }),
  ).toBeVisible();
});

/**
 * @SVCs SVC_GW_0036
 */
test("snapshot_soft_delete_and_restore_in_the_portal", async ({ page }) => {
  await login(page, "alice");
  await page
    .getByRole("navigation", { name: "Main" })
    .getByRole("link", { name: "Marketplaces" })
    .click();
  const name = uniqueName("retain");

  await page.getByRole("button", { name: "Register marketplace" }).click();
  await page.getByLabel("Name").fill(name);
  await page.getByLabel("Clone URL").fill(process.env.E2E_UPSTREAM_URL ?? "file:///tmp/e2e-upstream");
  await page.getByRole("button", { name: "Register", exact: true }).click();
  await page.getByRole("button", { name: `Ingest ${name}` }).click();
  await expect(page.getByText("held", { exact: true })).toBeVisible();

  // Retention lives with the snapshot, on the marketplace's own page.
  await page.getByRole("link", { name, exact: true }).click();
  await page.getByRole("button", { name: /Delete snapshot \d+/ }).click();
  await expect(page.getByText("deleted", { exact: true })).toBeVisible();
  await expect(page.getByText(/restorable until/)).toBeVisible();

  await page.getByRole("button", { name: /Restore snapshot \d+/ }).click();
  await expect(page.getByText("deleted", { exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: /Delete snapshot \d+/ })).toBeVisible();
});

/**
 * @SVCs SVC_GW_0030
 */
test("audit_page_exports_the_ledger_and_lists_sinks", async ({ page }) => {
  await login(page, "alice");
  await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Audit log" }).click();

  const sinkName = uniqueName("sink");
  await expect(page.getByRole("button", { name: "Add sink" })).toBeDisabled();
  // Nothing listens there: the sink still registers, and its position is what this page shows.
  await page.getByLabel("Sink name").fill(sinkName);
  await page.getByLabel("Target URL").fill("http://127.0.0.1:9/ingest");
  await page.getByRole("button", { name: "Add sink" }).click();
  await expect(page.getByTestId("sink-secret")).toBeVisible();
  await page.getByRole("button", { name: "Done" }).click();

  const sinkRow = page.getByRole("row", { name: new RegExp(sinkName) });
  await expect(sinkRow).toBeVisible();
  await expect(sinkRow.getByText(/entries/)).toBeVisible();

  // The export affordance is a real download of the NDJSON stream.
  const download = page.waitForEvent("download");
  await page.getByRole("link", { name: /Download ledger/ }).click();
  expect((await download).suggestedFilename()).toBe("audit-ledger.ndjson");
});

/** Registers the tainted fixture and ingests it, returning its marketplace card. */
async function registerTainted(page: Page, prefix: string) {
  await page
    .getByRole("navigation", { name: "Main" })
    .getByRole("link", { name: "Marketplaces" })
    .click();
  const name = uniqueName(prefix);

  await page.getByRole("button", { name: "Register marketplace" }).click();
  await page.getByLabel("Name").fill(name);
  await page
    .getByLabel("Clone URL")
    .fill(process.env.E2E_TAINTED_UPSTREAM_URL ?? "file:///tmp/e2e-tainted");
  await page.getByRole("button", { name: "Register", exact: true }).click();
  await page.getByRole("button", { name: `Ingest ${name}` }).click();

  // Scoped to this marketplace's own card: earlier tests in the run leave their
  // own held snapshots on the page.
  const card = page.locator("[data-slot=card]").filter({ hasText: name });
  await expect(card.getByText("held", { exact: true })).toBeVisible();
  return card;
}

/**
 * @SVCs SVC_GW_0042
 */
test("vetting_verdicts_are_shown_and_a_blocked_snapshot_cannot_be_approved", async ({ page }) => {
  await login(page, "alice");
  const card = await registerTainted(page, "tainted");

  // The chain blocked it, and the table says so before anything is clicked.
  await expect(card.getByText("vetting blocked")).toBeVisible();

  await card.getByRole("button", { name: /Approve snapshot \d+/ }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByText("prompt-injection").first()).toBeVisible();
  await expect(dialog.getByText("instruction-override").first()).toBeVisible();

  // Fail-closed at the surface too: there is no reason field to type past the gate with,
  // and the confirm control stays disabled while anything is uncovered.
  const confirm = dialog.getByRole("button", { name: /Confirm approval of snapshot \d+/ });
  await expect(confirm).toBeDisabled();
  await expect(dialog.getByLabel("Reason for approving anyway")).toHaveCount(0);
});

/**
 * @SVCs SVC_GW_0047
 */
test("a_finding_is_waived_from_the_review_surface_and_the_waiver_is_listed", async ({ page }) => {
  await login(page, "alice");
  const card = await registerTainted(page, "waived");
  await expect(card.getByText("vetting blocked")).toBeVisible();

  await card.getByRole("button", { name: /Approve snapshot \d+/ }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByText("instruction-override").first()).toBeVisible();

  // Waive every blocking finding the server named; approval unblocks only when none is left.
  const confirm = dialog.getByRole("button", { name: /Confirm approval of snapshot \d+/ });
  await expect(confirm).toBeDisabled();

  for (let i = 0; i < 10; i++) {
    const waive = dialog.getByRole("button", { name: /^Waive finding / }).first();
    if ((await waive.count()) === 0) break;
    const rule = (await waive.getAttribute("aria-label"))!.replace("Waive finding ", "");
    await waive.click();
    await dialog.getByLabel("Justification").first().fill("accepted for the pilot ring");
    await dialog.getByRole("button", { name: `Record waiver for ${rule}` }).click();
    // The finding is now shown as accepted rather than blocking.
    await expect(dialog.getByText(new RegExp(`waived by alice until`)).first()).toBeVisible();
  }

  // Cleared, but visibly by an acceptance rather than by a clean chain.
  await expect(dialog.getByText("vetting clear with waivers")).toBeVisible();
  await expect(dialog.getByText("accepted for the pilot ring").first()).toBeVisible();

  await expect(confirm).toBeEnabled();
  await confirm.click();
  await expect(card.getByText("approved", { exact: true })).toBeVisible();
});

/**
 * The whole retraction loop in a browser: waive a finding to publish content the chain objects
 * to, withdraw the acceptance, re-vet, and watch the gateway take the content back.
 *
 * This is the sharpest end of the feature — under enforcement the gateway unpublishes content on
 * its own — so the acceptance test drives it the way an operator would, and checks the two things
 * an operator needs afterwards: why it went, and who already had it.
 *
 * @SVCs SVC_GW_0055
 */
test("a_revoked_snapshot_shows_its_violation_and_who_had_already_fetched_it", async ({ page }) => {
  await login(page, "alice");
  const card = await registerTainted(page, "revoked");
  await expect(card.getByText("vetting blocked")).toBeVisible();

  // Publish it the only sanctioned way: an explicit, justified, expiring acceptance per finding.
  await card.getByRole("button", { name: /Approve snapshot \d+/ }).click();
  const dialog = page.getByRole("dialog");
  const confirm = dialog.getByRole("button", { name: /Confirm approval of snapshot \d+/ });
  for (let i = 0; i < 10; i++) {
    const waive = dialog.getByRole("button", { name: /^Waive finding / }).first();
    if ((await waive.count()) === 0) break;
    const rule = (await waive.getAttribute("aria-label"))!.replace("Waive finding ", "");
    await waive.click();
    await dialog.getByLabel("Justification").first().fill("accepted for the pilot ring");
    await dialog.getByRole("button", { name: `Record waiver for ${rule}` }).click();
    await expect(dialog.getByText(/waived by alice until/).first()).toBeVisible();
  }
  await expect(confirm).toBeEnabled();
  await confirm.click();
  await expect(card.getByText("approved", { exact: true })).toBeVisible();

  // Withdraw every acceptance. The gate closes immediately; publication does not move yet —
  // that is exactly the gap continuous re-vetting exists to close.
  await card.getByRole("link").first().click();
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  // Wait for the evidence to load before counting controls: an empty list here would silently
  // mean "revoked nothing", and the test would then be asserting against a still-waived snapshot.
  const waivers = page.getByRole("region", { name: "Waivers" });
  await expect(waivers).toBeVisible();
  const revoke = waivers.getByRole("button", { name: /^Revoke waiver \d+/ });
  await expect(revoke.first()).toBeVisible();
  for (let i = 0; i < 10 && (await revoke.count()) > 0; i++) {
    const before = await revoke.count();
    await revoke.first().click();
    await expect(revoke).toHaveCount(before - 1, { timeout: 15_000 });
  }
  await expect(waivers.getByText("active")).toHaveCount(0);
  // The gate has closed again, but publication has not moved: this is the gap re-vetting closes.
  await expect(page.getByText("approved", { exact: true }).first()).toBeVisible();

  // Now ask for fresh evidence. Under enforcement the answer takes the content away.
  await page.getByRole("button", { name: /^Re-vet snapshot \d+/ }).first().click();
  await expect(page.getByText(/revoked by a re-vetting violation/)).toBeVisible({ timeout: 30_000 });

  // The state, the reason, the identity that revoked it, and the blast radius are all on the page.
  await expect(page.getByText("revoked", { exact: true }).first()).toBeVisible();
  await expect(page.getByText(/re-vetting violation/).first()).toBeVisible();
  await expect(page.getByText(/revoked by/).first()).toBeVisible();
  const affected = page.getByRole("region", { name: /Identities that fetched snapshot \d+/ });
  await expect(affected).toBeVisible();
  // Nobody cloned this fixture through the facade, and the panel says so rather than showing
  // an empty list a reviewer would have to interpret.
  await expect(affected.getByText(/Nobody fetched this snapshot/)).toBeVisible();

  // A revoked snapshot is not re-vetted again — it is not being served — and the way back is a
  // fresh decision on the marketplaces page.
  await expect(page.getByRole("button", { name: /^Re-vet snapshot \d+/ })).toHaveCount(0);
});
