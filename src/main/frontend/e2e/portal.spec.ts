import { execFileSync } from "node:child_process";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { expect, test, type Locator, type Page } from "@playwright/test";

/**
 * Real-browser acceptance: unmodified gateway + PostgreSQL + mock OIDC IdP
 * (compose.e2e.yaml), driven through the actual login redirect. Assertions use
 * role/name queries (accessibility tree) per ADR 0003.
 */

async function login(page: Page, username: string) {
  await page.goto("/");
  // The gateway redirects unauthenticated browsers to the IdP (GW_AUTH_0002).
  await page.waitForURL(/9090/);
  await page.getByPlaceholder(/enter any user/i).fill(username);
  await page.getByRole("button", { name: /sign.?in/i }).click();
  // Back on the portal: the sidebar navigation is the landmark.
  await expect(page.getByRole("navigation", { name: "Main" })).toBeVisible();
}

/**
 * Submits the register form's "Register" button — ticking the "Register anyway"
 * acknowledgement first if the duplicate-URL warning is showing. Every test in this
 * file points at the same small set of upstream fixtures (the E2E_*_UPSTREAM_URL
 * envs), so once the first marketplace is registered against a given fixture, every
 * later registration against that same fixture is a legitimate collision that the
 * warning correctly flags — not a bug in the gating.
 */
async function submitRegister(page: Page) {
  const registerAnyway = page.getByRole("checkbox", { name: "Register anyway" });
  if (await registerAnyway.isVisible().catch(() => false)) {
    await registerAnyway.check();
  }
  await page.getByRole("button", { name: "Register", exact: true }).click();
}

/** Opens the signed-in user's menu in the header — identity, roles, theme, tokens, sign out. */
async function openUserMenu(page: Page) {
  await page.getByRole("button", { name: /Signed in as/ }).click();
}

/** Personal tokens are a per-user surface: the way in is the user menu, not the navigation. */
async function openTokens(page: Page) {
  await openUserMenu(page);
  await page.getByRole("menuitem", { name: "Your tokens" }).click();
  await expect(page.getByRole("heading", { name: "Access tokens" })).toBeVisible();
}

function uniqueName(prefix: string) {
  // A millisecond timestamp alone collides when parallel workers (CI shards,
  // --repeat-each) register in the same tick — the duplicate-name 500 then
  // leaves the register dialog open and the test times out on the Ingest
  // button. A random suffix keeps the name unique across workers.
  return `${prefix}${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

/**
 * The marketplaces page is a table: each row expands in place to its snapshot review sub-table,
 * where Ingest and the Approve/Reject/Provenance actions live. Every test that acts on a
 * marketplace's snapshots opens its row first.
 */
async function expandMarketplace(page: Page, name: string) {
  await page.getByRole("button", { name: `Expand ${name}`, exact: true }).click();
}

/**
 * The expanded snapshots region for one marketplace — the review sub-table. Scoping to it keeps
 * a test off the held/approved snapshots other tests in the same run leave on the page.
 */
function marketplaceRegion(page: Page, name: string): Locator {
  return page.getByRole("region", { name: `Snapshots of ${name}` });
}

/**
 * @SVCs SVC_GW_INGEST_0007
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
  await submitRegister(page);
  await expect(page.getByText(`Marketplace '${name}' registered`)).toBeVisible();

  // The review actions live in the row's expandable snapshot sub-table.
  await expandMarketplace(page, name);
  const region = marketplaceRegion(page, name);
  await region.getByRole("button", { name: `Ingest ${name}` }).click();
  await expect(region.getByText("held", { exact: true })).toBeVisible();

  // Approval goes through the review dialog: the reviewer sees the verdicts first (GW_VETTING_0005).
  await region.getByRole("button", { name: /Approve snapshot \d+/ }).click();
  await page.getByRole("button", { name: /Confirm approval of snapshot \d+/ }).click();
  await expect(region.getByText("approved", { exact: true })).toBeVisible();

  await region.getByRole("button", { name: /Provenance of snapshot \d+/ }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByText("Decided by")).toBeVisible();
  await expect(dialog.getByText("alice")).toBeVisible();
  await page.keyboard.press("Escape");

  // Detail view: nothing awaits a decision, so the served snapshot is the open card, and its
  // plugin/skill inventory is a tab on it (GW_INGEST_0008).
  await page.getByRole("link", { name, exact: true }).click();
  const served = page.getByRole("region", { name: /^Snapshot \d+$/ });
  await served.getByRole("tab", { name: "Inventory" }).click();
  await expect(served.getByText("hello", { exact: true }).first()).toBeVisible();
  // The tab strip scrolls sideways on a phone but never vertically: no scrollbar beside the tabs.
  const strip = served.getByRole("tablist");
  expect(await strip.evaluate((el) => el.scrollHeight - el.clientHeight)).toBe(0);

  // Beside it, what approving it would change (GW_INGEST_0022). This marketplace has exactly one
  // snapshot — the one on screen — so there is no approved baseline, and the panel says so
  // rather than rendering an empty diff.
  await served.getByRole("tab", { name: "Diff" }).click();
  await expect(
    page.getByRole("region", { name: /Changes in snapshot \d+ since the last approved snapshot/ }),
  ).toContainText("no baseline to compare against");
});

/**
 * Separation of duties as a lone administrator meets it, in a real browser (GW_APPROVAL_0011).
 *
 * One identity registers the marketplace, pulls the content and then opens the review dialog —
 * which is the whole point of this test running against the acceptance deployment's default
 * configuration rather than a special one. The dialog must say plainly that this is a
 * self-approval and must still let it through, because the alternative is a gateway that becomes
 * unapprovable the moment a single-person deployment upgrades. The refusal half of the rule is
 * a deployment mode this suite's one mock-IdP identity cannot exercise; it is verified over HTTP.
 *
 * @SVCs SVC_GW_APPROVAL_0011
 */
test("the_approve_dialog_warns_that_the_reviewer_supplied_the_content_and_still_allows_it", async ({
  page,
}) => {
  await login(page, "alice");
  await page
    .getByRole("navigation", { name: "Main" })
    .getByRole("link", { name: "Marketplaces" })
    .click();
  const name = uniqueName("solo");

  await page.getByRole("button", { name: "Register marketplace" }).click();
  await page.getByLabel("Name").fill(name);
  await page.getByLabel("Clone URL").fill(process.env.E2E_UPSTREAM_URL ?? "file:///tmp/e2e-upstream");
  await submitRegister(page);
  await expect(page.getByText(`Marketplace '${name}' registered`)).toBeVisible();
  await expandMarketplace(page, name);
  const card = marketplaceRegion(page, name);
  await card.getByRole("button", { name: `Ingest ${name}` }).click();

  await card.getByRole("button", { name: /Approve snapshot \d+/ }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByText(/Four-eyes rule/)).toContainText("registered this marketplace");
  await expect(dialog.getByText(/Four-eyes rule/)).toContainText("ingested this snapshot");

  // Warn mode: said, recorded, and allowed.
  const confirm = dialog.getByRole("button", { name: /Confirm approval of snapshot \d+/ });
  await expect(confirm).toBeEnabled();
  await confirm.click();
  await expect(card.getByText("approved", { exact: true })).toBeVisible();
});

/**
 * The shell's identity surface, against a real login: the menu names the session and every
 * role it holds with where that role came from. In this deployment the only role is mapped
 * from the identity provider's group claim, which is exactly what the menu has to say.
 *
 * @SVCs SVC_GW_AUTH_0044
 */
test("the_user_menu_names_the_session_and_each_role_with_its_source", async ({ page }) => {
  await login(page, "alice");
  await openUserMenu(page);

  const menu = page.getByRole("menu");
  await expect(menu).toContainText("Signed in as");
  await expect(menu).toContainText("alice");
  await expect(menu).toContainText("admin — from your identity provider");
  // It reports; it does not gate. Nothing here is hidden or disabled by role.
  await expect(menu.getByRole("menuitem", { name: "Your tokens" })).toBeVisible();
  await expect(menu.getByRole("menuitem", { name: /Theme:/ })).toBeVisible();

  // Escape closes the menu and returns focus to its trigger.
  await page.keyboard.press("Escape");
  await expect(page.getByRole("menu")).toHaveCount(0);
  await expect(page.getByRole("button", { name: /Signed in as/ })).toBeFocused();
});

/**
 * Tokens moved out of the estate-wide navigation and under the user menu; the address they
 * have always had still resolves, so a bookmark or a documentation link is not broken by it.
 *
 * @SVCs SVC_GW_AUTH_0046
 */
test("tokens_are_reached_from_the_user_menu_and_the_route_still_resolves", async ({ page }) => {
  await login(page, "alice");
  await expect(
    page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Access tokens" }),
  ).toHaveCount(0);

  await openTokens(page);

  await page.goto("/tokens");
  await expect(page.getByRole("heading", { name: "Access tokens" })).toBeVisible();
});

/**
 * Signing out ends the session: the next page the browser asks for is the identity provider's,
 * which is the only thing that proves the cookie no longer authenticates anything.
 *
 * @SVCs SVC_GW_AUTH_0045
 */
test("signing_out_ends_the_session", async ({ page }) => {
  await login(page, "alice");
  await openUserMenu(page);
  await page.getByRole("menuitem", { name: "Sign out" }).click();

  await page.waitForURL(/9090/);
  await expect(page.getByPlaceholder(/enter any user/i)).toBeVisible();
});

/**
 * @SVCs SVC_GW_AUTH_0005, SVC_GW_AUTH_0031
 */
test("token_cleartext_is_shown_once_and_revocation_marks_it_revoked", async ({ page }) => {
  await login(page, "alice");
  await openTokens(page);

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
  // Nothing has authenticated with it yet, and the column says so rather than leaving a blank
  // an operator would have to interpret (SVC_GW_AUTH_0031).
  await expect(row.getByText("never", { exact: true })).toBeVisible();

  await row.getByRole("button", { name: `Revoke token ${tokenName}` }).click();
  await expect(row.getByText("revoked")).toBeVisible();
});

/**
 * @SVCs SVC_GW_WEBHOOK_0004
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
  await submitRegister(page);
  await expandMarketplace(page, marketplaceName);
  const marketplaceRow = marketplaceRegion(page, marketplaceName);
  await marketplaceRow.getByRole("button", { name: `Ingest ${marketplaceName}` }).click();
  await expect(marketplaceRow.getByText("held", { exact: true })).toBeVisible();

  await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Webhooks" }).click();
  await expect(
    page.getByRole("row", { name: new RegExp(`snapshot\\.ingested ${subscriberName}`) }),
  ).toBeVisible();
});

/**
 * @SVCs SVC_GW_RETENTION_0006
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
  await submitRegister(page);
  await expandMarketplace(page, name);
  const region = marketplaceRegion(page, name);
  await region.getByRole("button", { name: `Ingest ${name}` }).click();
  await expect(region.getByText("held", { exact: true })).toBeVisible();

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
 * @SVCs SVC_GW_AUDIT_0006
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

/**
 * Registers the tainted fixture and ingests it, returning its marketplace card and the name it
 * was registered under — the name is what addresses its detail page.
 */
async function registerTaintedNamed(page: Page, prefix: string): Promise<{ card: Locator; name: string }> {
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
  await submitRegister(page);
  await expandMarketplace(page, name);

  // Scoped to this marketplace's own expanded snapshots region: earlier tests in the run
  // leave their own held snapshots on the page.
  const card = marketplaceRegion(page, name);
  await card.getByRole("button", { name: `Ingest ${name}` }).click();
  await expect(card.getByText("held", { exact: true })).toBeVisible();
  return { card, name };
}

/** The same, for the tests that only need the card. */
async function registerTainted(page: Page, prefix: string): Promise<Locator> {
  return (await registerTaintedNamed(page, prefix)).card;
}

/**
 * Waive every blocking finding named in the review dialog, one waiver round-trip
 * at a time. Each waiver POST re-renders the dialog and detaches its buttons, so
 * nothing here holds an element across the mutation — and, crucially, nothing
 * does a bare `.first().getAttribute(...)`: that call AUTO-WAITS for a matching
 * element, so on a slow runner it hangs for the full test timeout when the
 * re-render lands between counting the buttons and reading the label (#68).
 * Instead each iteration reads all labels in one atomic, non-waiting DOM pass
 * (`evaluateAll` returns [] when none remain), acts on a button addressed by
 * that label, and only advances once a web-first assertion has seen the waived
 * finding's own button disappear.
 */
async function waiveAllFindings(dialog: Locator) {
  const waiveButtons = dialog.getByRole("button", { name: /^Waive finding / });
  // A finding shown as accepted rather than blocking. The badge appears only when
  // the waiver POST's refetch has committed, which makes its count the loop's
  // settle signal: a waive button is HIDDEN while its inline form is open and
  // briefly REAPPEARS between the form closing and the refetch landing, so
  // "the button is gone" can be observed mid-flight and is NOT a safe signal.
  const waivedBadges = dialog.getByText(/waived by alice until/);
  // The findings load asynchronously after the dialog opens; both callers use a
  // tainted fixture, so an empty list here means "not loaded yet", never "clean".
  await expect(waiveButtons.first()).toBeVisible();
  for (let i = 0; i < 10; i++) {
    // The previous round-trip has fully landed (badge-count poll below), so this
    // atomic, non-waiting snapshot reads a settled DOM — never a bare
    // `.first().getAttribute(...)`, which auto-waits and hangs on slow runners
    // when a re-render lands between counting and reading (#68).
    const labels = await waiveButtons.evaluateAll((els) =>
      els.map((el) => el.getAttribute("aria-label")),
    );
    if (labels.length === 0) break;
    const label = labels[0];
    if (label == null) {
      throw new Error("Waive button has no aria-label; cannot name the finding to waive");
    }
    const rule = label.replace("Waive finding ", "");
    const waivedBefore = await waivedBadges.count();
    const justification = dialog.getByLabel("Justification").first();
    // Open the inline waive form. A stray re-render can still detach or move the
    // button mid-click, so the click is guarded (skipped once the form is open)
    // and retried until the form has actually appeared.
    await expect(async () => {
      if (!(await justification.isVisible())) {
        await dialog.getByRole("button", { name: label, exact: true }).click({ timeout: 2_000 });
      }
      await expect(justification).toBeVisible({ timeout: 1_000 });
    }).toPass({ timeout: 15_000 });
    await justification.fill("accepted for the pilot ring");
    await dialog.getByRole("button", { name: `Record waiver for ${rule}` }).click();
    // The round-trip is complete only when the refetch commits and at least one
    // more finding is shown as accepted rather than blocking (a snapshot-scope
    // waiver may suppress several findings of the same rule at once).
    await expect
      .poll(async () => waivedBadges.count(), { timeout: 15_000 })
      .toBeGreaterThan(waivedBefore);
  }
  await expect(waiveButtons).toHaveCount(0);
}

/**
 * @SVCs SVC_GW_VETTING_0005
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
 * The chain drawn as a chain, on the surface where a reviewer reads it: the marketplace detail
 * page, where the report is inline rather than inside the approve dialog.
 *
 * @SVCs SVC_GW_VETTING_0031
 */
test("the_vetting_chain_is_drawn_as_a_flow_and_a_node_opens_its_evidence", async ({ page }) => {
  await login(page, "alice");
  const { name } = await registerTaintedNamed(page, "flow");
  await page.getByRole("link", { name, exact: true }).click();

  // The chain of the snapshot, in the order it ran: ingestion, the vetters, the aggregation,
  // and the gate the reviewer is standing at.
  const flow = page.getByRole("list", { name: /^Vetting chain of snapshot \d+$/ }).first();
  await expect(flow).toBeVisible();
  const steps = await flow.getByRole("button").evaluateAll((els) =>
    els.map((el) => el.getAttribute("aria-label") ?? ""),
  );
  expect(steps.at(0)).toContain("Ingest");
  expect(steps.at(-2)).toContain("Outcome");
  expect(steps.at(-1)).toContain("Approval");
  // Every state is a word, not a colour: the blocked chain says so on the outcome node itself.
  expect(steps.at(-2)).toContain("blocked");
  const injection = steps.find((label) => label.includes("prompt-injection"));
  expect(injection).toBeDefined();

  // A node is a control, and it opens that node's own evidence.
  await flow.getByRole("button", { name: injection!, exact: true }).click();
  const detail = page.getByRole("dialog");
  await expect(detail.getByText("instruction-override").first()).toBeVisible();

  // And the administrator surface: the effective chain of the marketplace itself, with the
  // source of each vetter's state (GW_VETTING_0029.5).
  await detail.press("Escape");
  const chain = page.getByRole("list", { name: `Vetting chain of ${name}` });
  await expect(chain).toBeVisible();
  await expect(chain.getByRole("button", { name: /prompt-injection, enabled$/ })).toBeVisible();
});

/**
 * The two chain-level controls an administrator has, driven the way an administrator would drive
 * them, and then the consequence on a snapshot.
 *
 * The reordering is operated by keyboard alone — focus the named movement control, press Enter —
 * because that is the requirement, not an implementation detail: an ordering gesture with no
 * keyboard equivalent is a control that some of the people who read this console do not have.
 *
 * @SVCs SVC_GW_VETTING_0034
 */
test("an_admin_sets_the_chain_mode_and_order_and_a_stopped_run_says_so", async ({ page }) => {
  await login(page, "alice");

  // Registered but not yet ingested: the moment the chain is actually worth configuring, and the
  // only one at which this can be driven end to end without an approval in between.
  await page
    .getByRole("navigation", { name: "Main" })
    .getByRole("link", { name: "Marketplaces" })
    .click();
  const name = uniqueName("chainmode");
  await page.getByRole("button", { name: "Register marketplace" }).click();
  await page.getByLabel("Name").fill(name);
  await page
    .getByLabel("Clone URL")
    .fill(process.env.E2E_TAINTED_UPSTREAM_URL ?? "file:///tmp/e2e-tainted");
  await submitRegister(page);
  await page.getByRole("link", { name, exact: true }).click();

  // Stop the chain at the first failure for this marketplace.
  const mode = page.getByRole("group", { name: "When a vetter fails" });
  await expect(mode).toBeVisible();
  await mode.getByRole("button", { name: "Stop after a failure" }).click();
  await expect(mode.getByRole("button", { name: "Stop after a failure" })).toHaveAttribute(
    "aria-pressed",
    "true",
  );

  // Move the vetter that objects to this content to the front, without a pointer: focus the
  // named movement control and press Enter. The requirement is the keyboard path, not the arrow.
  const order = page.getByRole("list", { name: `Vetter order of ${name}` });
  await order.getByRole("button", { name: "Move prompt-injection up" }).focus();
  await page.keyboard.press("Enter");
  await expect(order.getByRole("listitem").first()).toContainText("prompt-injection");
  await page.getByRole("button", { name: "Save order" }).click();
  await expect(page.getByRole("button", { name: "Save order" })).toBeDisabled();

  // The first snapshot therefore runs prompt-injection first, and the chain stops there.
  await page
    .getByRole("navigation", { name: "Main" })
    .getByRole("link", { name: "Marketplaces" })
    .click();
  await expandMarketplace(page, name);
  const card = marketplaceRegion(page, name);
  await card.getByRole("button", { name: `Ingest ${name}` }).click();
  await expect(card.getByText("held", { exact: true })).toBeVisible();

  await page.getByRole("link", { name, exact: true }).click();
  const flow = page.getByRole("list", { name: /^Vetting chain of snapshot \d+$/ }).first();
  // The headline says the chain stopped early and where, so a shorter chain is not read as a
  // cleaner one, and the vetters it never reached say so on their own nodes.
  await expect(page.getByText("Stopped at step 1")).toBeVisible();
  await expect(flow.getByRole("button", { name: /secret-scan, not reached$/ })).toBeVisible();

  // And the gate is shut: a run that stopped early is not a run that found nothing.
  await expect(flow.getByRole("button", { name: /Approval, closed$/ })).toBeVisible();
});

/**
 * @SVCs SVC_GW_VETTING_0010
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

  await waiveAllFindings(dialog);

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
 * @SVCs SVC_GW_VETTING_0018
 */
test("a_revoked_snapshot_shows_its_violation_and_who_had_already_fetched_it", async ({ page }) => {
  await login(page, "alice");
  const card = await registerTainted(page, "revoked");
  await expect(card.getByText("vetting blocked")).toBeVisible();

  // Publish it the only sanctioned way: an explicit, justified, expiring acceptance per finding.
  await card.getByRole("button", { name: /Approve snapshot \d+/ }).click();
  const dialog = page.getByRole("dialog");
  const confirm = dialog.getByRole("button", { name: /Confirm approval of snapshot \d+/ });
  await waiveAllFindings(dialog);
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
 * The set-up panel leads on a marketplace that is serving, and on one that is not it says the
 * thing the portal could not say before: a clone is answered with 404 until a snapshot is
 * approved. Both states are exercised on the same marketplace, before and after the approval,
 * so the panel is shown to react to the estate rather than to the fixture.
 *
 * @SVCs SVC_GW_AUTH_0043
 */
test("the_setup_panel_leads_when_serving_and_explains_the_held_case", async ({ page }) => {
  await login(page, "alice");
  await page
    .getByRole("navigation", { name: "Main" })
    .getByRole("link", { name: "Marketplaces" })
    .click();
  const name = uniqueName("lead");
  await page.getByRole("button", { name: "Register marketplace" }).click();
  await page.getByLabel("Name").fill(name);
  await page.getByLabel("Clone URL").fill(process.env.E2E_UPSTREAM_URL ?? "file:///tmp/e2e-upstream");
  await submitRegister(page);

  // Nothing approved yet: the lead slot explains the refusal the consumer would otherwise read
  // as a broken gateway, on the page and again inside the wizard.
  await page.getByRole("link", { name, exact: true }).click();
  await expect(page.getByTestId("setup-lead")).toContainText("Not being served yet");
  await expect(page.getByTestId("setup-held-notice").first()).toContainText("404");
  await page.getByRole("button", { name: "Set up a client" }).click();
  await expect(page.getByTestId("setup-held-notice").last()).toContainText("404");
  // The credential line is the primary copy target, held or not.
  await expect(page.getByRole("button", { name: "Copy credential command" })).toBeVisible();
  await expect(
    page.getByRole("group", { name: "Expires" }).getByRole("button", { name: "30 days" }),
  ).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: "Done" }).click();

  // Ingest and approve, then the same slot leads with the way in.
  await page
    .getByRole("navigation", { name: "Main" })
    .getByRole("link", { name: "Marketplaces" })
    .click();
  await expandMarketplace(page, name);
  const card = marketplaceRegion(page, name);
  await card.getByRole("button", { name: `Ingest ${name}` }).click();
  await expect(card.getByText("held", { exact: true })).toBeVisible();
  await card.getByRole("button", { name: /Approve snapshot \d+/ }).click();
  await page.getByRole("button", { name: /Confirm approval of snapshot \d+/ }).click();
  await expect(card.getByText("approved", { exact: true })).toBeVisible();

  await page.getByRole("link", { name, exact: true }).click();
  await expect(page.getByTestId("setup-lead")).toContainText("Use this marketplace");
  await expect(page.getByTestId("setup-held-notice")).toHaveCount(0);
});

/**
 * @SVCs SVC_GW_OBSERVABILITY_0004, SVC_GW_AUTH_0031
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
  await submitRegister(page);
  await expandMarketplace(page, name);
  const card = marketplaceRegion(page, name);
  await card.getByRole("button", { name: `Ingest ${name}` }).click();
  await expect(card.getByText("held", { exact: true })).toBeVisible();
  await card.getByRole("button", { name: /Approve snapshot \d+/ }).click();
  await page.getByRole("button", { name: /Confirm approval of snapshot \d+/ }).click();
  await expect(card.getByText("approved", { exact: true })).toBeVisible();

  // A PAT minted in the portal, then a real `git clone` through the facade with it.
  await openTokens(page);
  const tokenName = uniqueName("adopttoken");
  await page.getByLabel("Token name").fill(tokenName);
  await page.getByRole("button", { name: "Create token" }).click();
  const pat = await page.getByTestId("token-cleartext").textContent();
  expect(pat).toBeTruthy();
  await page.getByRole("button", { name: "Done" }).click();

  const base = new URL(process.env.E2E_BASE_URL ?? "http://localhost:18081");
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

  // And the credential that did the fetching now says so on its own row: a real facade
  // authentication is what moves Last used off "never" (SVC_GW_AUTH_0031).
  await openTokens(page);
  const tokenRow = page.getByRole("row", { name: new RegExp(tokenName) });
  await expect(tokenRow).toBeVisible();
  await expect(tokenRow.getByText("never", { exact: true })).toHaveCount(0);
});

/**
 * The setup wizard composes everything a client needs from the page's own origin, and holds the
 * show-once line: a token minted inside it fills the snippets only while the wizard is open.
 *
 * @SVCs SVC_GW_AUTH_0014
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
  await submitRegister(page);
  await page.getByRole("link", { name, exact: true }).click();

  await page.getByRole("button", { name: "Set up a client" }).click();
  const origin = new URL(page.url()).origin;
  await expect(page.getByTestId("wizard-add-command")).toHaveText(
    `claude plugin marketplace add ${origin}/git/${name}`,
  );
  await expect(page.getByTestId("wizard-credential-config")).toContainText(new URL(origin).host);
  // No token yet: the snippets carry a placeholder, never a secret.
  await expect(page.getByTestId("wizard-clone-command")).toContainText("<YOUR_TOKEN>");

  // Minting goes through the same show-once flow as the tokens page. The name field arrives with
  // a default, so the disabled-until-valid rule is asserted by emptying it rather than by finding
  // it empty.
  await page.getByLabel("Token name").clear();
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
 * The reviewer's file explorer on a real held-vs-served delta: approve one commit, advance the
 * upstream fixture (modify the skill, add a file), re-ingest, and inspect the held snapshot in
 * its card's Contents tab — nested tree, inertly rendered SKILL.md, and the file's diff against
 * the served baseline.
 *
 * The address is asserted twice, because that is the claim: selecting a file puts it in the
 * URL, and opening that URL cold — a second approver following a pasted link — restores the
 * same bytes with nothing else carried over.
 *
 * @SVCs SVC_GW_APPROVAL_0005, SVC_GW_INGEST_0032
 */
test("snapshot_contents_are_explored_on_an_address_that_restores_the_same_file", async ({
  page,
}) => {
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
  await submitRegister(page);
  await expandMarketplace(page, name);
  const card = marketplaceRegion(page, name);
  await card.getByRole("button", { name: `Ingest ${name}` }).click();
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
  await card.getByRole("button", { name: `Ingest ${name}` }).click();
  await expect(card.getByText("held", { exact: true })).toBeVisible();

  await page.getByRole("link", { name, exact: true }).click();
  // The held snapshot is the newest awaiting a decision, so it is the card that is open; its
  // one-line delta is against the served commit, and names it.
  const heldCard = page.getByRole("region", { name: /^Snapshot \d+$/ });
  await expect(heldCard.getByTestId("snapshot-delta")).toContainText(/2 files · \+\d+ −\d+ · vs [0-9a-f]{8}/);
  await heldCard.getByRole("tab", { name: "Contents" }).click();

  await expect(page).toHaveURL(/\/marketplaces\/[^/?]+\?snapshot=\d+&tab=contents$/);
  const tree = page.getByRole("navigation", { name: /File tree of snapshot \d+/ });
  await expect(tree).toBeVisible();

  // A real tree: the skill is reached by opening directories, not by scrolling a flat list.
  await tree.getByRole("button", { name: "plugins" }).click();
  await tree.getByRole("button", { name: "hello" }).click();
  await tree.getByRole("button", { name: "skills" }).click();
  await tree.getByRole("button", { name: "hello" }).last().click();
  await tree.getByRole("button", { name: /SKILL\.md/ }).click();

  // Rendered inertly: the hostile embedded HTML is visible as text and never becomes an element.
  await expect(page.getByRole("heading", { name: "Hello skill" })).toBeVisible();
  await expect(page.getByText("<img src=x onerror=alert(1)>")).toBeVisible();
  await expect(page.locator("main img")).toHaveCount(0);

  // The selection is in the address — this is the link an approver sends to the second one.
  await expect(page).toHaveURL(
    /\?snapshot=\d+&tab=contents&path=plugins%2Fhello%2Fskills%2Fhello%2FSKILL\.md$/,
  );
  const deepLink = page.url();

  // And the link is enough on its own: opened cold, it restores the same file, revealed.
  await page.goto("about:blank");
  await page.goto(deepLink);
  await expect(page.getByRole("heading", { name: "Hello skill" })).toBeVisible();
  await expect(
    page.getByRole("navigation", { name: /File tree of snapshot \d+/ }).getByRole("button", {
      name: /SKILL\.md/,
    }),
  ).toHaveAttribute("aria-current", "true");

  // The file's own delta against the served baseline, without the tree going anywhere.
  await page.getByRole("button", { name: /vs served/ }).click();
  await expect(page.getByText("+Now with a changed instruction.")).toBeVisible();
  await expect(page.getByRole("navigation", { name: /File tree of snapshot \d+/ })).toBeVisible();

  // The added file is marked as added in the tree, without the reviewer opening it.
  const added = page
    .getByRole("navigation", { name: /File tree of snapshot \d+/ })
    .getByRole("button", { name: /docs-NEW\.md/ });
  await expect(added).toContainText("added");
  await added.click();
  await expect(page.getByRole("region", { name: "Selected file" })).toContainText("added");

  // Links sent before the card existed still open: the full-width route renders the same explorer.
  const snapshotId = new URL(deepLink).searchParams.get("snapshot");
  await page.goto(
    `/marketplaces/${name}/snapshots/${snapshotId}/files?path=plugins%2Fhello%2Fskills%2Fhello%2FSKILL.md`,
  );
  await expect(page.getByRole("heading", { name: "Hello skill" })).toBeVisible();
});

/**
 * The mock identity provider puts a group claim in every token, and the gateway
 * runs with role enforcement on and that group as its only admin mapping — so
 * every test above already depends on this path. Here it is named: the session
 * holds admin, and its source is the claim rather than a grant row.
 *
 * @SVCs SVC_GW_AUTH_0015.3, SVC_GW_AUTH_0025
 */
test("the_session_holds_an_admin_role_derived_from_the_identity_providers_group_claim", async ({
  page,
}) => {
  await login(page, "alice");

  const me = await page.request.get("/api/v1/me");
  expect(me.ok()).toBeTruthy();
  const body = await me.json();

  // Authorization is always enforced, so there is no flag to report and its absence is the
  // assertion (GW_AUTH_0025). The role below is what proves enforcement reached a real login.
  expect(body.rolesEnabled).toBeUndefined();
  expect(body.claimsTruncated).toBe(false);
  expect(body.roles).toContainEqual({ role: "admin", marketplace: null, source: "claim" });
});

/**
 * The estate-wide half of the chain settings, driven the way an administrator would drive it: set
 * the default from the Vetting page, see which marketplaces depart from it, and bring one back.
 *
 * The global mode is deliberately set to `run-all`, which is what it already resolves to. The
 * assertion is not the behaviour — it is that the setting now exists, which the control reports by
 * naming its source as the global setting rather than as the absence of one. Writing a behavioural
 * default here would change what every other marketplace in this shared gateway runs.
 *
 * Clearing is the other half, and the one that could not be faked in the browser: an override equal
 * to the default still pins the marketplace, so the proof is the source going back to the global
 * setting, not the value staying the same.
 *
 * @SVCs SVC_GW_VETTING_0035, SVC_GW_VETTING_0036
 */
test("an_admin_sets_the_default_chain_and_clears_a_marketplaces_override", async ({ page }) => {
  await login(page, "alice");

  // A marketplace that departs from the default in exactly one way.
  await page
    .getByRole("navigation", { name: "Main" })
    .getByRole("link", { name: "Marketplaces" })
    .click();
  const name = uniqueName("estate");
  await page.getByRole("button", { name: "Register marketplace" }).click();
  await page.getByLabel("Name").fill(name);
  await page
    .getByLabel("Clone URL")
    .fill(process.env.E2E_TAINTED_UPSTREAM_URL ?? "file:///tmp/e2e-tainted");
  await submitRegister(page);
  await page.getByRole("link", { name, exact: true }).click();

  const marketplaceMode = page.getByRole("group", { name: "When a vetter fails" });
  await marketplaceMode.getByRole("button", { name: "Stop after a failure" }).click();
  await expect(page.getByText(/Currently set for this marketplace/)).toBeVisible();

  // The page that governs the estate is one hop from the card that governs one marketplace.
  await page.getByRole("link", { name: "Govern the chain across the estate" }).click();
  await expect(page.getByRole("heading", { name: "Vetting", level: 1 })).toBeVisible();

  // And it is a real address, not only a client-side hop: a bookmarked /vetting has to resolve
  // through the gateway's SPA forward rather than 404.
  await page.goto("/vetting");
  await expect(page.getByRole("heading", { name: "Vetting", level: 1 })).toBeVisible();

  // The default chain, written globally. What the assertion can see is the source: a setting now
  // exists where none did. It is then put back to run-all, so the estate this shared gateway serves
  // to every other test ends where it started — with a global row that resolves to the default.
  const defaultMode = page.getByRole("group", { name: "When a vetter fails" });
  await defaultMode.getByRole("button", { name: "Stop after a failure" }).click();
  await expect(page.getByText(/Currently from the global setting/)).toBeVisible();
  await defaultMode.getByRole("button", { name: "Run every vetter" }).click();
  await expect(
    defaultMode.getByRole("button", { name: "Run every vetter" }),
  ).toHaveAttribute("aria-pressed", "true");

  // The marketplace that departs shows up as a row saying what it departs in.
  await expect(page.getByRole("row", { name: new RegExp(name) })).toContainText(
    "mode: stop-after-fail",
  );

  // Clearing is a removal, not a write of the default value.
  await page.getByRole("button", { name: `Clear every chain override on ${name}` }).click();
  await expect(page.getByRole("row", { name: new RegExp(name) })).toHaveCount(0);

  // And the marketplace now resolves from the global setting, which is what "cleared" has to mean.
  await page
    .getByRole("navigation", { name: "Main" })
    .getByRole("link", { name: "Marketplaces" })
    .click();
  await page.getByRole("link", { name, exact: true }).click();
  await expect(page.getByText(/Currently from the global setting/)).toBeVisible();
});

/**
 * The seam between a chain change and the approval gate, through the surfaces a reviewer actually
 * uses: evidence goes stale because an administrator changed the chain, the reviewer is told with
 * both chains named, refreshes it in place, and approves — an approval that was never blocked.
 *
 * @SVCs SVC_GW_VETTING_0038, SVC_GW_VETTING_0038.1
 */
test("a_chain_change_marks_held_evidence_superseded_and_the_reviewer_refreshes_it", async ({
  page,
}) => {
  await login(page, "alice");
  await page
    .getByRole("navigation", { name: "Main" })
    .getByRole("link", { name: "Marketplaces" })
    .click();
  const name = uniqueName("staleness");
  await page.getByRole("button", { name: "Register marketplace" }).click();
  await page.getByLabel("Name").fill(name);
  await page.getByLabel("Clone URL").fill(process.env.E2E_UPSTREAM_URL ?? "file:///tmp/e2e-upstream");
  await submitRegister(page);

  await expandMarketplace(page, name);
  const card = marketplaceRegion(page, name);
  await card.getByRole("button", { name: `Ingest ${name}` }).click();
  await expect(card.getByText("held", { exact: true })).toBeVisible();

  // The vetting evidence is read on the marketplace detail page, which is also where the chain
  // is configured — the two facts this test is about live on one screen.
  await page.getByRole("link", { name, exact: true }).click();
  const vetting = page.getByRole("region", { name: /Vetting of snapshot \d+/ }).first();
  await expect(vetting).toBeVisible();
  // Vetted against the chain in force, so nothing is said about it.
  await expect(vetting.getByText(/different chain than this marketplace runs now/)).toHaveCount(0);

  // An administrator changes the chain. The mode is part of its identity, so this is a chain
  // change in exactly the sense the requirement means — and it re-runs nothing.
  const mode = page.getByRole("group", { name: "When a vetter fails" });
  await mode.getByRole("button", { name: "Stop after a failure" }).click();
  await expect(mode.getByRole("button", { name: "Stop after a failure" })).toHaveAttribute(
    "aria-pressed",
    "true",
  );

  // The stored run is now evidence from a superseded chain, and the reviewer is told so — with
  // both chains named, because "it is stale" without saying how is not actionable.
  const detailVetting = page.getByRole("region", { name: /Vetting of snapshot \d+/ }).first();
  await expect(detailVetting.getByText(/different chain than this marketplace runs now/)).toBeVisible();
  await expect(detailVetting.getByText(/mode=run-all/).first()).toBeVisible();
  await expect(detailVetting.getByText(/mode=stop-after-fail/).first()).toBeVisible();
  await expect(detailVetting.getByText(/Approval is not blocked by this/)).toBeVisible();

  // Refreshed in place: the chain runs again, and the snapshot is still held.
  await detailVetting.getByRole("button", { name: /Re-run the vetting chain on snapshot \d+/ }).click();
  await expect(
    detailVetting.getByText(/different chain than this marketplace runs now/),
  ).toHaveCount(0);

  // And the approval was never blocked by any of it.
  await page
    .getByRole("navigation", { name: "Main" })
    .getByRole("link", { name: "Marketplaces" })
    .click();
  await expandMarketplace(page, name);
  const after = marketplaceRegion(page, name);
  await after.getByRole("button", { name: /Approve snapshot \d+/ }).click();
  await page.getByRole("button", { name: /Confirm approval of snapshot \d+/ }).click();
  await expect(after.getByText("approved", { exact: true })).toBeVisible();
});
