import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { ThemeProvider } from "next-themes";
import type { ReactNode } from "react";
import { createMemoryRouter, MemoryRouter, Route, RouterProvider, Routes } from "react-router-dom";
import { expect, test } from "vitest";
import { createQueryClient } from "@/api/query-client";
import type { components } from "@/api/types.gen";
import { AppLayout } from "@/components/app-layout";
import { marketplace } from "@/test/msw-handlers";
import { server } from "@/test/msw-server";
import { AdoptionPage } from "./adoption";
import { AuditPage } from "./audit";
import { AuditSinksPage } from "./audit-sinks";
import {
  MarketplaceActivityPage,
  MarketplaceLayout,
  MarketplaceReviewPage,
  MarketplaceSnapshotsPage,
} from "./marketplace-detail";
import { MarketplacesPage } from "./marketplaces";
import { OverviewPage } from "./overview";
import { WebhooksPage } from "./webhooks";

type Role = components["schemas"]["EffectiveRole"];

const NO_ROLE: Role[] = [];
const AUDITOR: Role[] = [{ role: "auditor", source: "grant" }];
const ADMIN: Role[] = [{ role: "admin", source: "config" }];
const approverOf = (marketplace: string): Role[] => [{ role: "approver", marketplace, source: "grant" }];

function signedInAs(roles: Role[]) {
  server.use(
    http.get("/api/v1/me", () =>
      HttpResponse.json({ username: "bob", roles, claimsTruncated: false }),
    ),
  );
}

/** Records every request to a path the role would be refused, so a test can say none was made. */
function watchRefusedReads() {
  const reads: string[] = [];
  const listener = ({ request }: { request: Request }) => {
    const path = new URL(request.url).pathname;
    if (/^\/api\/v1\/(audit|adoption|webhooks)/.test(path)) reads.push(path);
  };
  server.events.on("request:start", listener);
  return { reads, stop: () => server.events.removeListener("request:start", listener) };
}

function renderWith(element: ReactNode, at = "/", client?: QueryClient) {
  const queryClient = client ?? new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[at]}>{element}</MemoryRouter>
    </QueryClientProvider>,
  );
}

/** The fixture marketplace is corp-marketplace, with held snapshot 1, deleted 2 and revoked 3. */
function renderMarketplace(name = "corp-marketplace", section = "", client?: QueryClient) {
  if (name === "corp-marketplace") {
    server.use(http.get("/api/v1/marketplaces", () => HttpResponse.json([marketplace])));
  } else {
    server.use(
      http.get("/api/v1/marketplaces", () =>
        HttpResponse.json([
          {
            id: 9,
            name,
            url: "https://example.com/other.git",
            createdAt: "2026-08-14T09:00:00Z",
            snapshots: [
              { id: 1, marketplaceId: 9, sha: "aaaabbbbccccddddeeeeffff0000111122223333", state: "held", createdAt: "2026-08-14T10:00:00Z" },
            ],
          },
        ]),
      ),
    );
  }
  return renderWith(
    <Routes>
      <Route path="/marketplaces/:name" element={<MarketplaceLayout />}>
        <Route index element={<MarketplaceReviewPage />} />
        <Route path="snapshots" element={<MarketplaceSnapshotsPage />} />
        <Route path="activity" element={<MarketplaceActivityPage />} />
      </Route>
    </Routes>,
    `/marketplaces/${name}${section ? `/${section}` : ""}`,
    client,
  );
}

const MUTATIONS = /^(Ingest |Approve snapshot|Reject snapshot|Delete snapshot|Restore snapshot|Re-vet snapshot|Waive |Revoke waiver|Select )/;

async function heldCard() {
  const card = await screen.findByRole("region", { name: "Snapshot 1" });
  // The vetting report has rendered its findings, so a waive control would be on screen by now.
  await within(card).findByText(/an AWS access key id is committed/);
  return card;
}

/**
 * A session with no role, and an auditor, see a held snapshot and its evidence but no control
 * that changes anything — and the decision says which role it needs instead of disappearing.
 *
 * @SVCs SVC_GW_AUTH_0072
 */
test("a_session_with_no_role_or_only_the_auditor_role_is_offered_no_mutation", async () => {
  for (const roles of [NO_ROLE, AUDITOR]) {
    signedInAs(roles);
    const page = renderMarketplace();
    const card = await heldCard();
    expect(screen.getByRole("heading", { name: "corp-marketplace" })).toBeInTheDocument();
    const offered = screen
      .queryAllByRole("button")
      .map((button) => button.getAttribute("aria-label") ?? button.textContent ?? "")
      .filter((name) => MUTATIONS.test(name));
    expect(offered).toEqual([]);
    expect(within(card).getByTestId("snapshot-decision")).toHaveTextContent(
      "Approving or rejecting a snapshot of corp-marketplace needs the approver role for it.",
    );
    page.unmount();

    const snapshots = renderMarketplace("corp-marketplace", "snapshots");
    expect(await screen.findByText("deleted")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Restore snapshot 2" })).not.toBeInTheDocument();
    snapshots.unmount();

    const list = renderWith(<MarketplacesPage />);
    expect(await screen.findByRole("link", { name: "corp-marketplace" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Register marketplace" })).not.toBeInTheDocument();
    list.unmount();
  }
});

/**
 * An approver decides its own marketplace and nothing else; delete, restore and registration stay
 * the administrator's, who is offered every control.
 *
 * @SVCs SVC_GW_AUTH_0073
 */
test("an_approver_decides_only_its_own_marketplace_and_an_administrator_every_one", async () => {
  // Approver of corp-marketplace, on another marketplace.
  signedInAs(approverOf("corp-marketplace"));
  let page = renderMarketplace("partner-marketplace");
  let card = await heldCard();
  expect(screen.queryByRole("button", { name: /^Ingest / })).not.toBeInTheDocument();
  expect(within(card).queryByRole("button", { name: "Approve snapshot 1" })).not.toBeInTheDocument();
  expect(within(card).queryByRole("button", { name: /^Waive / })).not.toBeInTheDocument();
  expect(within(card).getByTestId("snapshot-decision")).toHaveTextContent(
    "needs the approver role for it",
  );
  page.unmount();

  // The same approver, on its own marketplace.
  page = renderMarketplace();
  card = await heldCard();
  expect(screen.getByRole("button", { name: "Ingest corp-marketplace" })).toBeEnabled();
  expect(within(card).getByRole("button", { name: "Approve snapshot 1" })).toBeInTheDocument();
  expect(within(card).getByRole("button", { name: "Reject snapshot 1" })).toBeEnabled();
  expect(
    within(card).getByRole("button", { name: "Waive aws-access-key-id at plugins/hello/DEPLOY.md:5" }),
  ).toBeInTheDocument();
  expect(within(card).queryByRole("button", { name: "Delete snapshot 1" })).not.toBeInTheDocument();
  page.unmount();
  let list = renderWith(<MarketplacesPage />);
  expect(await screen.findByRole("link", { name: "corp-marketplace" })).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Register marketplace" })).not.toBeInTheDocument();
  list.unmount();

  // An administrator, everywhere.
  signedInAs(ADMIN);
  page = renderMarketplace();
  card = await heldCard();
  expect(screen.getByRole("button", { name: "Ingest corp-marketplace" })).toBeEnabled();
  expect(within(card).getByRole("button", { name: "Approve snapshot 1" })).toBeInTheDocument();
  expect(within(card).getByRole("button", { name: "Delete snapshot 1" })).toBeInTheDocument();
  page.unmount();
  list = renderWith(<MarketplacesPage />);
  expect(await screen.findByRole("button", { name: "Register marketplace" })).toBeInTheDocument();
  list.unmount();
});

/**
 * The ledger, adoption and integration pages say which role they need instead of showing the
 * server's refusal, an empty ledger, a download or a count that never resolves — and make none
 * of the reads the server would refuse. An auditor reads every one.
 *
 * @SVCs SVC_GW_AUTH_0074
 */
test("the_pages_behind_the_auditor_role_state_it_and_make_no_refused_read", async () => {
  signedInAs(NO_ROLE);
  const watch = watchRefusedReads();
  for (const Page of [AuditPage, AdoptionPage, WebhooksPage, AuditSinksPage]) {
    const view = renderWith(<Page />);
    expect(await screen.findByRole("alert")).toHaveTextContent("This page needs the auditor role.");
    expect(screen.queryByRole("link", { name: /Download ledger/ })).not.toBeInTheDocument();
    expect(screen.queryByText("No fetches recorded yet.")).not.toBeInTheDocument();
    view.unmount();
  }
  const activity = renderMarketplace("corp-marketplace", "activity");
  expect(await screen.findByRole("alert")).toHaveTextContent("This section needs the auditor role.");
  activity.unmount();
  const overview = renderWith(<OverviewPage />);
  expect(await screen.findByText("Access tokens")).toBeInTheDocument();
  expect(screen.queryByText("Fetch ledger")).not.toBeInTheDocument();
  expect(screen.queryByText(/ledger entr/)).not.toBeInTheDocument();
  overview.unmount();
  watch.stop();
  expect(watch.reads).toEqual([]);

  signedInAs(AUDITOR);
  const audit = renderWith(<AuditPage />);
  expect(await screen.findByRole("link", { name: /Download ledger/ })).toBeInTheDocument();
  expect(screen.queryByText(/needs the auditor role/)).not.toBeInTheDocument();
  audit.unmount();
  const webhooks = renderWith(<WebhooksPage />);
  expect(await screen.findByRole("heading", { name: "Webhooks" })).toBeInTheDocument();
  expect(screen.queryByText(/needs the auditor role/)).not.toBeInTheDocument();
  // Reading the listings is not managing them.
  expect(screen.queryByRole("button", { name: /New subscriber/ })).not.toBeInTheDocument();
  webhooks.unmount();
  const ledgerCard = renderWith(<OverviewPage />);
  expect(await screen.findByText(/^\d[\d,]* ledger entr/)).toBeInTheDocument();
  ledgerCard.unmount();
});

function renderShell(at: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter([{ path: "*", element: <AppLayout /> }], { initialEntries: [at] });
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider attribute="class" defaultTheme="system" enableSystem disableTransitionOnChange>
        <RouterProvider router={router} />
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

/**
 * The navigation offers only what the session can read: the oversight and integration entries,
 * and a marketplace's activity, are an auditor's — not a session with no role, nor an approver's.
 *
 * @SVCs SVC_GW_AUTH_0075
 */
test("the_navigation_leaves_out_the_pages_the_session_cannot_read", async () => {
  for (const roles of [NO_ROLE, approverOf("corp-marketplace")]) {
    signedInAs(roles);
    const shell = renderShell("/marketplaces/corp-marketplace");
    const main = await screen.findByRole("navigation", { name: "Main" });
    const sections = await within(main).findByRole("list", { name: "corp-marketplace sections" });
    expect(within(sections).getByRole("link", { name: "Snapshots" })).toBeInTheDocument();
    expect(within(sections).queryByRole("link", { name: "Activity" })).not.toBeInTheDocument();
    for (const name of ["Audit log", "Adoption", "Webhooks", "Audit sinks", "Vetting chain"]) {
      expect(within(main).queryByRole("link", { name })).not.toBeInTheDocument();
    }
    expect(within(main).queryByText("Oversight")).not.toBeInTheDocument();
    expect(within(main).queryByText("Configuration")).not.toBeInTheDocument();
    shell.unmount();
  }

  signedInAs(AUDITOR);
  renderShell("/marketplaces/corp-marketplace");
  const main = await screen.findByRole("navigation", { name: "Main" });
  expect(await within(main).findByRole("link", { name: "Audit log" })).toBeInTheDocument();
  for (const name of ["Adoption", "Webhooks", "Audit sinks", "Activity"]) {
    expect(within(main).getByRole("link", { name })).toBeInTheDocument();
  }
  expect(within(main).queryByRole("link", { name: "Vetting chain" })).not.toBeInTheDocument();
});

/**
 * A role revoked while the page is open: the next control pressed is refused, and the refusal
 * re-reads the session, so the page withdraws the controls without a reload.
 *
 * @SVCs SVC_GW_AUTH_0076
 */
test("a_refused_request_rereads_the_session_and_withdraws_the_revoked_controls", async () => {
  const user = userEvent.setup();
  signedInAs(approverOf("corp-marketplace"));
  renderMarketplace("corp-marketplace", "", createQueryClient());
  const card = await heldCard();
  const ingest = screen.getByRole("button", { name: "Ingest corp-marketplace" });
  expect(within(card).getByRole("button", { name: "Reject snapshot 1" })).toBeInTheDocument();

  // The grant is revoked on the server; the page still holds the old session read.
  signedInAs(NO_ROLE);
  server.use(
    http.post("/api/v1/marketplaces/:name/ingest", () =>
      HttpResponse.json(
        { status: 403, detail: "this operation requires a role you do not hold" },
        { status: 403 },
      ),
    ),
  );
  await user.click(ingest);

  await waitFor(() =>
    expect(screen.queryByRole("button", { name: "Ingest corp-marketplace" })).not.toBeInTheDocument(),
  );
  expect(within(card).queryByRole("button", { name: "Reject snapshot 1" })).not.toBeInTheDocument();
  expect(within(card).getByTestId("snapshot-decision")).toHaveTextContent("needs the approver role");
});
