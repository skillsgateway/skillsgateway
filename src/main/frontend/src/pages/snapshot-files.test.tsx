import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { expect, test, vi } from "vitest";
import { filesPage, treePage } from "@/test/msw-handlers";
import { server } from "@/test/msw-server";
import { SnapshotFilesPage } from "./snapshot-files";

const ROUTE = "/marketplaces/:name/snapshots/:id/files";

/** The tree, scoped: the file pane names the same path on its copy control. */
const tree = () => within(screen.getByRole("navigation", { name: /File tree of snapshot/ }));

/** The address itself is the feature, so the tests read it rather than infer it. */
function Address() {
  const location = useLocation();
  return <output aria-label="address">{`${location.pathname}${location.search}`}</output>;
}

function renderPage(entry = "/marketplaces/corp-marketplace/snapshots/1/files") {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path={ROUTE} element={<SnapshotFilesPage />} />
        </Routes>
        <Address />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

test("the_tree_is_nested_and_a_file_is_reached_by_opening_its_directories", async () => {
  const user = userEvent.setup();
  renderPage();
  await screen.findByRole("navigation", { name: "File tree of snapshot 1" });

  // Directories, not a wall of full paths: the leaf is not on screen until its parents are open.
  expect(screen.queryByRole("button", { name: /SKILL\.md/ })).not.toBeInTheDocument();
  await user.click(await screen.findByRole("button", { name: /^plugins,/ }));
  await user.click(await screen.findByRole("button", { name: /^hello,/ }));
  await user.click(await screen.findByRole("button", { name: /^skills,/ }));
  // Two directories are now named "hello"; the innermost is the one still closed.
  await waitFor(() => expect(screen.getAllByRole("button", { name: /^hello,/ })).toHaveLength(2));
  await user.click(screen.getAllByRole("button", { name: /^hello,/ }).at(-1) as HTMLElement);
  expect(await screen.findByRole("button", { name: /SKILL\.md/ })).toBeInTheDocument();
});

test("a_deep_link_restores_the_same_file_and_reveals_it_in_the_tree", async () => {
  renderPage(
    "/marketplaces/corp-marketplace/snapshots/1/files?path=plugins%2Fhello%2Fskills%2Fhello%2FSKILL.md",
  );

  // The file is rendered as Markdown, and its directories were opened to show where it sits.
  expect(await screen.findByRole("heading", { name: "Hello skill" })).toBeInTheDocument();
  expect(await tree().findByRole("button", { name: /SKILL\.md/ })).toHaveAttribute(
    "aria-current",
    "true",
  );
  // Inert: the hostile embedded HTML is text, and produced no element.
  expect(screen.getByText(/<img src=x onerror=alert\(1\)>/)).toBeInTheDocument();
  expect(screen.queryByRole("img")).not.toBeInTheDocument();
});

test("selecting_a_file_puts_its_path_in_the_address", async () => {
  const user = userEvent.setup();
  renderPage();
  await screen.findByRole("navigation", { name: "File tree of snapshot 1" });
  await user.click(await screen.findByRole("button", { name: /^data,/ }));
  await user.click(await screen.findByRole("button", { name: /huge\.txt/ }));

  expect(screen.getByLabelText("address")).toHaveTextContent(
    "/marketplaces/corp-marketplace/snapshots/1/files?path=data%2Fhuge.txt",
  );
  expect(
    await screen.findByText(/Truncated: showing the first part of 900000 bytes/),
  ).toBeInTheDocument();
});

/**
 * @SVCs SVC_GW_APPROVAL_0027, SVC_GW_APPROVAL_0026
 */
test("the_search_reports_its_matches_against_the_whole_snapshot", async () => {
  const user = userEvent.setup();
  renderPage();
  await screen.findByRole("navigation", { name: "File tree of snapshot 1" });
  // True totals, from the gateway: the snapshot's files, and how many differ from what is served.
  expect(screen.getByLabelText("Search paths")).toHaveAccessibleDescription("5 files, 3 changed");

  await user.type(screen.getByLabelText("Search paths"), "SKILL");
  // A match anywhere in the snapshot, found without opening a folder, shown by its full path.
  expect(
    await tree().findByRole("button", { name: /plugins\/hello\/skills\/hello\/SKILL\.md/ }),
  ).toHaveTextContent("plugins/hello/skills/hello/SKILL.md");
  expect(screen.getByLabelText("Search paths")).toHaveAccessibleDescription("1 matching of 5 files");

  await user.clear(screen.getByLabelText("Search paths"));
  await user.type(screen.getByLabelText("Search paths"), "nothing-matches-this");
  expect(await screen.findByText("No path matches that search.")).toBeInTheDocument();
  expect(screen.getByLabelText("Search paths")).toHaveAccessibleDescription("0 matching of 5 files");
});

/**
 * A search's matches are paged like any listing: past the first page, the rest are one control away.
 *
 * @SVCs SVC_GW_APPROVAL_0027
 */
test("a_search_with_more_matches_than_a_page_shows_the_rest_on_request", async () => {
  const user = userEvent.setup();
  const many = Array.from({ length: 2001 }, (_, i) => ({ path: `skills/s${i}/SKILL.md`, size: 1 }));
  server.use(
    http.get("/api/v1/snapshots/:id/files", ({ request }) =>
      HttpResponse.json(filesPage({ snapshotId: 1, sha: "abc", entries: many }, request)),
    ),
  );
  renderPage();
  await screen.findByRole("navigation", { name: "File tree of snapshot 1" });
  await user.type(screen.getByLabelText("Search paths"), "skill.md");

  const more = await tree().findByRole("button", { name: "Show 1 more (2000 of 2001 shown)" });
  expect(screen.getByLabelText("Search paths")).toHaveAccessibleDescription("2001 matching of 5 files");
  await user.click(more);
  expect(await tree().findByRole("button", { name: /skills\/s2000\/SKILL\.md/ })).toBeInTheDocument();
  expect(tree().queryByRole("button", { name: /Show .* more/ })).not.toBeInTheDocument();
}, 30_000);

/**
 * A folder is read when it is opened, and one wider than a page shows the rest on request, with
 * the folder's true size — nothing in the snapshot is out of reach.
 *
 * @SVCs SVC_GW_APPROVAL_0025, SVC_GW_APPROVAL_0026
 */
test("a_folder_wider_than_a_page_is_read_when_opened_and_shows_the_rest_on_request", async () => {
  const user = userEvent.setup();
  const wide = {
    snapshotId: 1,
    sha: "abc",
    entries: Array.from({ length: 601 }, (_, i) => ({
      path: `wide/f${String(i).padStart(4, "0")}.txt`,
      size: 5,
    })),
  };
  const opened: string[] = [];
  server.use(
    http.get("/api/v1/snapshots/:id/tree", ({ request }) => {
      opened.push(new URL(request.url).searchParams.get("dir") ?? "");
      return HttpResponse.json(treePage(wide, { entries: [] }, request));
    }),
  );
  renderPage();
  await screen.findByRole("navigation", { name: "File tree of snapshot 1" });
  expect(screen.getByLabelText("Search paths")).toHaveAccessibleDescription("601 files");
  // Only the root has been read; the folder's contents wait for the reviewer to open it.
  expect(opened).toEqual([""]);

  await user.click(await tree().findByRole("button", { name: "wide, 601 files" }));
  const more = await tree().findByRole("button", { name: "Show 101 more (500 of 601 shown)" });
  expect(tree().queryByRole("button", { name: /f0600\.txt/ })).not.toBeInTheDocument();
  await user.click(more);
  expect(await tree().findByRole("button", { name: /f0600\.txt/ })).toBeInTheDocument();
  expect(tree().getAllByRole("button", { name: /wide\/f\d{4}\.txt/ })).toHaveLength(601);
}, 30_000);

test("a_binary_blob_is_described_rather_than_rendered", async () => {
  const user = userEvent.setup();
  renderPage();
  await screen.findByRole("navigation", { name: "File tree of snapshot 1" });
  await user.click(await screen.findByRole("button", { name: /^assets,/ }));
  await user.click(await screen.findByRole("button", { name: /logo\.bin/ }));

  expect(await screen.findByText(/Binary file \(4096 bytes\)/)).toBeInTheDocument();
});

test("a_removed_path_is_in_the_tree_and_shows_what_approving_drops", async () => {
  const user = userEvent.setup();
  renderPage();
  await screen.findByRole("navigation", { name: "File tree of snapshot 1" });
  await user.click(await screen.findByRole("button", { name: /^docs,/ }));

  const removed = await screen.findByRole("button", { name: /OLD\.md/ });
  expect(removed).toHaveTextContent("removed");
  await user.click(removed);
  expect(
    await screen.findByText(/This path is served today and is not in this snapshot/),
  ).toBeInTheDocument();
  expect(screen.getByText("-# Old")).toBeInTheDocument();
});

test("a_file_is_compared_against_the_served_commit_without_leaving_it", async () => {
  const user = userEvent.setup();
  renderPage(
    "/marketplaces/corp-marketplace/snapshots/1/files?path=plugins%2Fhello%2Fskills%2Fhello%2FSKILL.md",
  );
  await screen.findByRole("heading", { name: "Hello skill" });

  await user.click(await screen.findByRole("button", { name: /vs served/ }));
  expect(await screen.findByText("+new instruction")).toBeInTheDocument();
  expect(screen.getByText("-old instruction")).toBeInTheDocument();
  // The tree did not go anywhere to make room for the diff.
  expect(screen.getByRole("navigation", { name: "File tree of snapshot 1" })).toBeInTheDocument();
});

test("an_unchanged_file_says_so_rather_than_showing_an_empty_diff", async () => {
  const user = userEvent.setup();
  renderPage("/marketplaces/corp-marketplace/snapshots/1/files?path=data%2Fhuge.txt");
  await screen.findByText(/Truncated: showing the first part/);

  await user.click(await screen.findByRole("button", { name: /vs served/ }));
  expect(await screen.findByText("Unchanged against the served commit.")).toBeInTheDocument();
});

test("a_refused_read_is_a_stated_condition_not_a_blank_pane", async () => {
  server.use(
    http.get("/api/v1/snapshots/:id/tree", () =>
      HttpResponse.json({ detail: "approver role required" }, { status: 403 }),
    ),
  );
  renderPage();

  expect(
    await screen.findByText("You cannot read this snapshot's contents."),
  ).toBeInTheDocument();
  expect(screen.getByRole("link", { name: /Back to corp-marketplace/ })).toBeInTheDocument();
});

test("a_snapshot_that_is_not_the_named_marketplaces_is_a_wrong_link", async () => {
  renderPage("/marketplaces/corp-marketplace/snapshots/999/files");

  expect(
    await screen.findByText("Snapshot 999 is not a snapshot of corp-marketplace."),
  ).toBeInTheDocument();
});

test("a_malformed_snapshot_address_asks_the_gateway_for_nothing", async () => {
  renderPage("/marketplaces/corp-marketplace/snapshots/not-a-number/files");

  expect(await screen.findByText("not-a-number is not a snapshot address.")).toBeInTheDocument();
  expect(
    screen.queryByRole("navigation", { name: /File tree/ }),
  ).not.toBeInTheDocument();
});

test("a_comparison_that_could_not_be_read_never_reads_as_unchanged", async () => {
  const user = userEvent.setup();
  server.use(
    http.get("/api/v1/snapshots/:id/diff", () =>
      HttpResponse.json({ detail: "baseline unreadable" }, { status: 500 }),
    ),
  );
  renderPage("/marketplaces/corp-marketplace/snapshots/1/files?path=data%2Fhuge.txt");
  await screen.findByText(/Truncated: showing the first part/);

  await user.click(await screen.findByRole("button", { name: /vs served/ }));
  expect(
    await screen.findByText("The comparison against the served commit could not be read."),
  ).toBeInTheDocument();
  expect(screen.queryByText("Unchanged against the served commit.")).not.toBeInTheDocument();
});

test("the_tree_marks_what_changed_so_the_delta_is_not_found_by_clicking", async () => {
  const user = userEvent.setup();
  renderPage();
  await screen.findByRole("navigation", { name: "File tree of snapshot 1" });
  await user.click(await tree().findByRole("button", { name: /^plugins,/ }));
  await user.click(await tree().findByRole("button", { name: /^hello,/ }));
  await user.click(await tree().findByRole("button", { name: /^skills,/ }));
  await waitFor(() => expect(tree().getAllByRole("button", { name: /^hello,/ })).toHaveLength(2));
  await user.click(tree().getAllByRole("button", { name: /^hello,/ }).at(-1) as HTMLElement);

  // The fixture's diff calls this one modified; the reviewer sees that without opening it.
  expect(await tree().findByRole("button", { name: /SKILL\.md/ })).toHaveTextContent("modified");
  // An unchanged file reports its size instead, in units rather than raw bytes.
  await user.click(await tree().findByRole("button", { name: /^assets,/ }));
  expect(await tree().findByRole("button", { name: /logo\.bin/ })).toHaveTextContent("4 KiB");
});

test("clearing_a_search_gives_back_the_shape_the_reviewer_had_built", async () => {
  const user = userEvent.setup();
  renderPage();
  await screen.findByRole("navigation", { name: "File tree of snapshot 1" });
  await user.click(await tree().findByRole("button", { name: /^data,/ }));
  expect(await tree().findByRole("button", { name: /huge\.txt/ })).toBeInTheDocument();

  // The search replaces the tree while it is up...
  await user.type(screen.getByLabelText("Search paths"), "SKILL");
  expect(await tree().findByRole("button", { name: /SKILL\.md/ })).toBeInTheDocument();
  expect(tree().queryByRole("button", { name: /huge\.txt/ })).not.toBeInTheDocument();

  // ...and clearing it gives back exactly the folders the reviewer had open.
  await user.clear(screen.getByLabelText("Search paths"));
  expect(await tree().findByRole("button", { name: /huge\.txt/ })).toBeInTheDocument();
  expect(tree().queryByRole("button", { name: /SKILL\.md/ })).not.toBeInTheDocument();
});

test("collapse_all_is_the_way_back_from_a_tree_a_deep_link_opened", async () => {
  const user = userEvent.setup();
  renderPage(
    "/marketplaces/corp-marketplace/snapshots/1/files?path=plugins%2Fhello%2Fskills%2Fhello%2FSKILL.md",
  );
  await screen.findByRole("heading", { name: "Hello skill" });
  expect(await tree().findByRole("button", { name: /SKILL\.md/ })).toBeInTheDocument();

  await user.click(await screen.findByRole("button", { name: "Collapse all" }));
  expect(tree().queryByRole("button", { name: /SKILL\.md/ })).not.toBeInTheDocument();
  // Nothing is open, so the control has nothing left to do.
  expect(screen.getByRole("button", { name: "Collapse all" })).toBeDisabled();
});

test("the_link_a_second_approver_gets_is_copied_by_a_control_not_the_address_bar", async () => {
  const user = userEvent.setup();
  const writeText = vi.fn().mockResolvedValue(undefined);
  vi.stubGlobal("navigator", { ...navigator, clipboard: { writeText } });
  renderPage("/marketplaces/corp-marketplace/snapshots/1/files?path=data%2Fhuge.txt");
  await screen.findByText(/Truncated: showing the first part/);

  await user.click(await screen.findByRole("button", { name: "Copy the link to data/huge.txt" }));
  expect(writeText).toHaveBeenCalledWith(window.location.href);
  vi.unstubAllGlobals();
});

test("a_tree_that_could_not_be_read_is_stated_rather_than_shown_empty", async () => {
  // The statuses come with the listing, so a failed read cannot leave an unmarked tree behind.
  server.use(
    http.get("/api/v1/snapshots/:id/tree", () =>
      HttpResponse.json({ detail: "baseline unreadable" }, { status: 500 }),
    ),
  );
  renderPage();

  expect(await screen.findByRole("alert")).toHaveTextContent(/baseline unreadable/);
  expect(screen.queryByRole("navigation", { name: /File tree/ })).not.toBeInTheDocument();
});

test("collapse_all_is_never_inert_over_a_search", async () => {
  const user = userEvent.setup();
  renderPage();
  await screen.findByRole("navigation", { name: "File tree of snapshot 1" });
  // Nothing opened by hand, so the control starts with nothing to do.
  expect(screen.getByRole("button", { name: "Collapse all" })).toBeDisabled();

  await user.type(screen.getByLabelText("Search paths"), "SKILL");
  expect(await tree().findByRole("button", { name: /SKILL\.md/ })).toBeInTheDocument();
  // The search owns the pane, so the control that clears it must be live.
  const collapse = screen.getByRole("button", { name: "Collapse all" });
  expect(collapse).toBeEnabled();

  await user.click(collapse);
  expect(tree().queryByRole("button", { name: /SKILL\.md/ })).not.toBeInTheDocument();
  expect(screen.getByLabelText("Search paths")).toHaveValue("");
});
