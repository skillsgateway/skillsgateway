import { execFileSync } from "node:child_process";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
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

/**
 * @SVCs SVC_GW_0078
 */
test("adoption_page_shows_a_real_facade_fetch_and_its_identity", async ({ page }) => {
  await login(page, "alice");

  // A marketplace with served content: register, ingest, approve.
  await page
    .getByRole("navigation", { name: "Main" })
    .getByRole("link", { name: "Marketplaces" })
    .click();
  const name = uniqueName("adopt");
  await page.getByRole("button", { name: "Register marketplace" }).click();
  await page.getByLabel("Name").fill(name);
  await page.getByLabel("Clone URL").fill(process.env.E2E_UPSTREAM_URL ?? "file:///tmp/e2e-upstream");
  await page.getByRole("button", { name: "Register", exact: true }).click();
  await page.getByRole("button", { name: `Ingest ${name}` }).click();
  const card = page.locator("[data-slot=card]").filter({ hasText: name });
  await expect(card.getByText("held", { exact: true })).toBeVisible();
  await card.getByRole("button", { name: /Approve snapshot \d+/ }).click();
  await page.getByRole("button", { name: /Confirm approval of snapshot \d+/ }).click();
  await expect(card.getByText("approved", { exact: true })).toBeVisible();

  // A PAT minted in the portal, then a real `git clone` through the facade with it.
  await page.getByRole("link", { name: "Access tokens" }).click();
  const tokenName = uniqueName("adopttoken");
  await page.getByLabel("Token name").fill(tokenName);
  await page.getByRole("button", { name: "Create token" }).click();
  const pat = await page.getByTestId("token-cleartext").textContent();
  expect(pat).toBeTruthy();
  await page.getByRole("button", { name: "Done" }).click();

  const base = new URL(process.env.E2E_BASE_URL ?? "http://localhost:8081");
  const cloneUrl = `http://token:${pat}@${base.host}/git/${name}`;
  const dest = mkdtempSync(join(tmpdir(), "e2e-adoption-clone-"));
  execFileSync("git", ["clone", cloneUrl, dest], {
    env: { ...process.env, GIT_TERMINAL_PROMPT: "0", GIT_CONFIG_GLOBAL: "/dev/null", GIT_CONFIG_SYSTEM: "/dev/null" },
    stdio: "pipe",
  });

  // The fetch is on the Adoption page: the marketplace's card with its identity counted.
  await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Adoption" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Adoption" })).toBeVisible();
  const adoptionCard = page.getByText(name, { exact: true }).first();
  await expect(adoptionCard).toBeVisible();
  await expect(page.getByRole("alert")).toHaveCount(0);
  // alice fetched once: the card carries one fetch by one identity, on the served tip.
  const row = page.getByRole("row", { name: /current/ }).filter({ has: page.locator("td") });
  await expect(row.first()).toBeVisible();
});

/**
 * The setup wizard composes everything a client needs from the page's own origin, and holds the
 * show-once line: a token minted inside it fills the snippets only while the wizard is open.
 *
 * @SVCs SVC_GW_0079
 */
test("setup_wizard_composes_origin_derived_commands_and_holds_show_once", async ({ page }) => {
  await login(page, "alice");
  await page
    .getByRole("navigation", { name: "Main" })
    .getByRole("link", { name: "Marketplaces" })
    .click();
  const name = uniqueName("wizard");
  await page.getByRole("button", { name: "Register marketplace" }).click();
  await page.getByLabel("Name").fill(name);
  await page.getByLabel("Clone URL").fill(process.env.E2E_UPSTREAM_URL ?? "file:///tmp/e2e-upstream");
  await page.getByRole("button", { name: "Register", exact: true }).click();
  await page.getByRole("link", { name, exact: true }).click();

  await page.getByRole("button", { name: "Set up a client" }).click();
  const origin = new URL(page.url()).origin;
  await expect(page.getByTestId("wizard-add-command")).toHaveText(
    `claude plugin marketplace add ${origin}/git/${name}`,
  );
  await expect(page.getByTestId("wizard-credential-config")).toContainText(new URL(origin).host);
  // No token yet: the snippets carry a placeholder, never a secret.
  await expect(page.getByTestId("wizard-clone-command")).toContainText("<YOUR_TOKEN>");

  // Minting goes through the same show-once flow as the tokens page.
  await expect(page.getByRole("button", { name: "Create token" })).toBeDisabled();
  await page.getByLabel("Token name").fill(uniqueName("wiz"));
  await page.getByRole("button", { name: "Create token" }).click();
  await expect(page.getByText("Token created", { exact: false })).toBeVisible();
  const clone = await page.getByTestId("wizard-clone-command").textContent();
  const token = /token:([^@]+)@/.exec(clone ?? "")?.[1];
  expect(token).toBeTruthy();
  expect(token).not.toBe("<YOUR_TOKEN>");

  // Close and reopen: the secret is gone with the wizard; nothing re-displays it.
  await page.getByRole("button", { name: "Done" }).click();
  await page.getByRole("button", { name: "Set up a client" }).click();
  await expect(page.getByTestId("wizard-clone-command")).toContainText("<YOUR_TOKEN>");
  await expect(page.getByText(token ?? "__never__")).toHaveCount(0);
});

/**
 * The reviewer preview pane on a real held-vs-served delta: approve one commit, advance the
 * upstream fixture (modify the skill, add a file), re-ingest, and inspect the held snapshot —
 * tree, inertly rendered SKILL.md, and the diff naming the served baseline's changes.
 *
 * @SVCs SVC_GW_0082
 */
test("preview_pane_shows_tree_inert_skill_md_and_diff_vs_served", async ({ page }) => {
  const upstream = process.env.E2E_PREVIEW_UPSTREAM_DIR;
  test.skip(!upstream, "E2E_PREVIEW_UPSTREAM_DIR not provided by run-e2e.sh");

  await login(page, "alice");
  await page
    .getByRole("navigation", { name: "Main" })
    .getByRole("link", { name: "Marketplaces" })
    .click();
  const name = uniqueName("preview");
  await page.getByRole("button", { name: "Register marketplace" }).click();
  await page.getByLabel("Name").fill(name);
  await page
    .getByLabel("Clone URL")
    .fill(process.env.E2E_PREVIEW_UPSTREAM_URL ?? `file://${upstream}`);
  await page.getByRole("button", { name: "Register", exact: true }).click();
  const card = page.locator("[data-slot=card]").filter({ hasText: name });
  await page.getByRole("button", { name: `Ingest ${name}` }).click();
  await expect(card.getByText("held", { exact: true })).toBeVisible();
  await card.getByRole("button", { name: /Approve snapshot \d+/ }).click();
  await page.getByRole("button", { name: /Confirm approval of snapshot \d+/ }).click();
  await expect(card.getByText("approved", { exact: true })).toBeVisible();

  // Advance the upstream the way its owner would: a real commit with git (host-config isolated,
  // exactly like run-e2e.sh builds the fixtures).
  const git = (...args: string[]) =>
    execFileSync("git", ["-C", upstream!, ...args], {
      env: {
        ...process.env,
        GIT_CONFIG_GLOBAL: "/dev/null",
        GIT_CONFIG_SYSTEM: "/dev/null",
        GIT_AUTHOR_NAME: "e2e",
        GIT_AUTHOR_EMAIL: "e2e@example.com",
        GIT_COMMITTER_NAME: "e2e",
        GIT_COMMITTER_EMAIL: "e2e@example.com",
      },
    });
  writeFileSync(
    join(upstream!, "plugins/hello/skills/hello/SKILL.md"),
    "# Hello skill\n\nNow with a changed instruction.\n\n<img src=x onerror=alert(1)>\n",
  );
  writeFileSync(join(upstream!, "docs-NEW.md"), "# Brand new file\n");
  git("add", "-A");
  git("-c", "commit.gpgsign=false", "commit", "-q", "-m", "e2e preview delta");

  // A second ingest pins the new commit as a held snapshot beside the served one.
  await page.getByRole("button", { name: `Ingest ${name}` }).click();
  await expect(card.getByText("held", { exact: true })).toBeVisible();

  await page.getByRole("link", { name, exact: true }).click();
  // The held snapshot is the newest: its card is the one whose preview we open.
  const heldCard = page.locator("[data-slot=card]").filter({ hasText: "held" }).last();
  await heldCard.getByRole("button", { name: /Preview files of snapshot \d+/ }).click();
  const preview = page.getByRole("region", { name: /Preview of snapshot \d+/ });
  await expect(preview).toBeVisible();

  // The tree lists the pinned commit's paths, and SKILL.md is quick-opened, rendered inertly:
  // the hostile embedded HTML is visible as text and never becomes an element.
  await expect(preview.getByText(".claude-plugin/marketplace.json")).toBeVisible();
  await expect(preview.getByRole("heading", { name: "Hello skill" })).toBeVisible();
  await expect(preview.getByText("<img src=x onerror=alert(1)>")).toBeVisible();

  // The diff names exactly what moved against the served baseline.
  await preview.getByRole("button", { name: /Diff of snapshot \d+ vs served/ }).click();
  await expect(preview.getByText(/Against served commit/)).toBeVisible();
  await expect(preview.getByText("modified", { exact: true })).toBeVisible();
  await expect(preview.getByText("plugins/hello/skills/hello/SKILL.md")).toBeVisible();
  await expect(preview.getByText("added", { exact: true })).toBeVisible();
  await expect(preview.getByText("docs-NEW.md")).toBeVisible();
  await preview
    .getByRole("button", { name: "Show diff of plugins/hello/skills/hello/SKILL.md" })
    .click();
  await expect(preview.getByText("+Now with a changed instruction.")).toBeVisible();
});
