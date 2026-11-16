import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { expect, test } from "vitest";
import { server } from "@/test/msw-server";
import { OverviewPage } from "./overview";

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <OverviewPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function ledgerOf(total: number, totalIsEstimate: boolean) {
  const requests: string[] = [];
  server.use(
    http.get("/api/v1/audit", ({ request }) => {
      requests.push(new URL(request.url).search);
      // One entry on the page, however many the ledger holds: the card must read the total.
      return HttpResponse.json({ entries: [{ id: total }], nextBefore: total - 1, total, totalIsEstimate });
    }),
  );
  return requests;
}

/**
 * The ledger card counts the ledger, not the page a browse read returns, and does not call every
 * entry a fetch: administrative actions and vetting bookkeeping are entries too.
 *
 * @SVCs SVC_GW_AUDIT_0008
 */
test("the_ledger_card_counts_every_entry_from_the_server_total", async () => {
  const requests = ledgerOf(1234, false);
  renderPage();

  expect(await screen.findByText("1,234 ledger entries")).toBeInTheDocument();
  expect(screen.queryByText(/recorded fetches/)).not.toBeInTheDocument();
  // A count costs one entry, not a page of a thousand.
  expect(requests).toEqual(["?limit=1"]);
});

/** Past the size where the server estimates rather than counts, the card says so. */
test("an_estimated_total_reads_as_about", async () => {
  ledgerOf(2_500_000, true);
  renderPage();

  expect(await screen.findByText("about 2,500,000 ledger entries")).toBeInTheDocument();
});

/**
 * Personal tokens have one home, the user menu's Security entry, so Overview offers none and makes
 * no read of the token list.
 *
 * @SVCs SVC_GW_AUTH_0086
 */
test("overview_has_no_access_tokens_card_and_reads_no_tokens", async () => {
  const reads: string[] = [];
  const listener = ({ request }: { request: Request }) => {
    const path = new URL(request.url).pathname;
    if (path.startsWith("/api/v1/tokens")) reads.push(path);
  };
  server.events.on("request:start", listener);
  renderPage();
  expect(await screen.findByRole("heading", { name: "Gateway Overview" })).toBeInTheDocument();
  expect(screen.queryByText("Access tokens")).not.toBeInTheDocument();
  expect(screen.queryByRole("link", { name: "Manage tokens" })).not.toBeInTheDocument();
  server.events.removeListener("request:start", listener);
  expect(reads).toEqual([]);
});

const FORMER = [
  {
    name: "corp-skills",
    location: "/var/lib/skills-gateway/hosted/corp-skills.git",
    lastCommitAt: "2026-10-01T09:30:00Z",
    headSha: "3f9c2ab",
  },
  { name: "tools", location: "s3://bucket/gateway/hosted/tools/" },
];

/**
 * An administrator is told, on Overview, how many repositories the removed hosting feature left in
 * the data store and where each is, with no control that deletes one; and is told nothing when
 * there are none.
 *
 * @SVCs SVC_GW_AUTH_0113
 */
test("an_administrator_is_told_which_former_hosted_repositories_remain", async () => {
  server.use(http.get("/api/v1/storage/former-hosted-repositories", () => HttpResponse.json(FORMER)));
  renderPage();

  const notice = await screen.findByRole("status", { name: "Former hosted repositories" });
  expect(notice).toHaveTextContent("This gateway no longer hosts marketplaces");
  expect(notice).toHaveTextContent("2 repositories from the former hosting feature are still in its data store");
  expect(notice).toHaveTextContent("corp-skills");
  expect(notice).toHaveTextContent("/var/lib/skills-gateway/hosted/corp-skills.git");
  expect(notice).toHaveTextContent("3f9c2ab");
  expect(notice).toHaveTextContent("s3://bucket/gateway/hosted/tools/");
  expect(within(notice).queryByRole("button")).not.toBeInTheDocument();
});

/** @SVCs SVC_GW_AUTH_0113 */
test("nothing_is_shown_when_no_former_hosted_repository_remains", async () => {
  renderPage();
  expect(await screen.findByRole("heading", { name: "Gateway Overview" })).toBeInTheDocument();
  expect(screen.queryByRole("status", { name: "Former hosted repositories" })).not.toBeInTheDocument();
});

/**
 * The list is an administrator's: any other session makes no request for it.
 *
 * @SVCs SVC_GW_AUTH_0113
 */
test("a_session_without_the_administrative_role_makes_no_request_for_the_list", async () => {
  server.use(
    http.get("/api/v1/me", () =>
      HttpResponse.json({ username: "ada", roles: [], claimsTruncated: false, version: "0.3.0" }),
    ),
  );
  const reads: string[] = [];
  const listener = ({ request }: { request: Request }) => {
    const path = new URL(request.url).pathname;
    if (path.startsWith("/api/v1/storage")) reads.push(path);
  };
  server.events.on("request:start", listener);
  renderPage();
  expect(await screen.findByRole("heading", { name: "Gateway Overview" })).toBeInTheDocument();
  await screen.findAllByText(/marketplaces/);
  server.events.removeListener("request:start", listener);
  expect(reads).toEqual([]);
  expect(screen.queryByRole("status", { name: "Former hosted repositories" })).not.toBeInTheDocument();
});
