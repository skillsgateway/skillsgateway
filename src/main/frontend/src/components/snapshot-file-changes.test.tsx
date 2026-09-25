import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, test } from "vitest";
import { diffPage } from "@/test/msw-handlers";
import { server } from "@/test/msw-server";
import { SnapshotFileChanges } from "./snapshot-file-changes";

function renderList() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <SnapshotFileChanges snapshotId={1} />
    </QueryClientProvider>,
  );
}

const list = () => within(screen.getByRole("list", { name: "Changed files in snapshot 1" }));

/**
 * The Diff tab lists every changed file: past the first page the rest are one control away, the
 * counts are over the whole diff, and each file opens its own diff in place.
 *
 * @SVCs SVC_GW_APPROVAL_0028, SVC_GW_APPROVAL_0026
 */
test("every_changed_file_is_listed_a_page_at_a_time_with_totals_over_the_whole_diff", async () => {
  const user = userEvent.setup();
  const entries = Array.from({ length: 603 }, (_, i) => ({
    path: `wide/f${String(i).padStart(4, "0")}.txt`,
    type: "modified" as const,
    binary: false,
    truncated: false,
    diff: `--- a\n+++ b\n@@ -1 +1,2 @@\n line\n+new ${i}\n`,
  }));
  server.use(
    http.get("/api/v1/snapshots/:id/diff", ({ request }) =>
      HttpResponse.json(
        diffPage({ snapshotId: 1, sha: "abc", baselineSha: "1111222233334444", entries }, request),
      ),
    ),
  );
  renderList();

  expect(await screen.findByText("603 files against")).toBeInTheDocument();
  expect(screen.getByText("603 modified")).toBeInTheDocument();
  expect(screen.getByText("+603 −0")).toBeInTheDocument();
  expect(list().getAllByRole("button")).toHaveLength(500);

  await user.click(screen.getByRole("button", { name: "Show 103 more (500 of 603 shown)" }));
  expect(await list().findByRole("button", { name: /wide\/f0602\.txt/ })).toBeInTheDocument();
  expect(list().getAllByRole("button")).toHaveLength(603);
  expect(screen.queryByRole("button", { name: /Show .* more/ })).not.toBeInTheDocument();

  const last = list().getByRole("button", { name: /wide\/f0602\.txt/ });
  await user.click(last);
  expect(last).toHaveAttribute("aria-expanded", "true");
  expect(screen.getByText("+new 602")).toBeInTheDocument();
}, 30_000);

test("with_nothing_served_the_list_says_every_file_is_new_rather_than_listing_them", async () => {
  server.use(
    http.get("/api/v1/snapshots/:id/diff", ({ request }) =>
      HttpResponse.json(
        diffPage(
          {
            snapshotId: 1,
            sha: "abc",
            entries: [{ path: "a.md", type: "added", binary: false, truncated: false }],
          },
          request,
        ),
      ),
    ),
  );
  renderList();

  expect(
    await screen.findByText(/Nothing is currently served for this marketplace, so its one file is new/),
  ).toBeInTheDocument();
});

test("a_removed_file_says_so_and_shows_what_approving_drops", async () => {
  const user = userEvent.setup();
  renderList();
  await screen.findByRole("list", { name: "Changed files in snapshot 1" });

  const removed = await list().findByRole("button", { name: /docs\/OLD\.md/ });
  expect(removed).toHaveTextContent("removed");
  await user.click(removed);
  expect(screen.getByText("-# Old")).toBeInTheDocument();
});

test("a_comparison_that_could_not_be_read_is_stated", async () => {
  server.use(
    http.get("/api/v1/snapshots/:id/diff", () =>
      HttpResponse.json({ detail: "baseline unreadable" }, { status: 500 }),
    ),
  );
  renderList();

  expect(await screen.findByRole("alert")).toHaveTextContent(
    "The comparison against the served commit could not be read",
  );
});
