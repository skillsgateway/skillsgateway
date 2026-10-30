import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { expect, test, vi } from "vitest";
import { compositeProvenance, snapshotContent } from "@/test/msw-handlers";
import { server } from "@/test/msw-server";
import { LocationHrefContext } from "@/lib/location-href";
import { FindingLocation } from "./finding-locations";
import { ProvenanceDetails } from "./provenance-details";
import { SnapshotFileTree } from "./snapshot-file-tree";
import { PluginInventory } from "./snapshot-inventory";
import { SourceView } from "./source-view";

const RLO = "\u202E";
const PDF = "\u202C";

/** No rendered text node may carry the character itself: it would be applied, not shown. */
function expectNoRawControl(element: HTMLElement) {
  expect(element.textContent).not.toMatch(/\p{Cf}/u);
}

/**
 * @SVCs SVC_GW_APPROVAL_0041
 */
test("a_plugin_name_description_and_skill_name_show_their_controls_as_tokens", async () => {
  const user = userEvent.setup();
  const plugin = {
    ...snapshotContent.plugins![1]!,
    name: `bidi-${RLO}evil${PDF}-name`,
    description: `Formats ${RLO}txt.exe${PDF} files`,
    skills: [{ name: `sk${RLO}ill`, path: "plugins/bidi/skills/skill/SKILL.md" }],
  };
  const { container } = render(<PluginInventory plugin={plugin} />);

  expect(screen.getByText("bidi-", { exact: false })).toHaveTextContent(
    "bidi-⟨U+202E⟩ (right-to-left override)evil⟨U+202C⟩ (pop directional formatting)-name",
  );
  expect(screen.getByText(/^Formats/)).toHaveTextContent("Formats ⟨U+202E⟩ (right-to-left override)txt.exe");
  await user.click(screen.getByRole("button", { name: "1 skill" }));
  const skills = screen.getByRole("region", { name: "skills of bidi-⟨U+202E⟩evil⟨U+202C⟩-name" });
  expect(within(skills).getByText("sk", { exact: false })).toHaveTextContent("sk⟨U+202E⟩ (right-to-left override)ill");
  expectNoRawControl(container);
});

/**
 * @SVCs SVC_GW_APPROVAL_0041
 */
test("a_file_name_and_a_finding_location_show_their_controls_as_tokens", () => {
  const path = `p/read${RLO}dm.sh`;
  const tree = render(
    <SnapshotFileTree
      entries={[{ kind: "file", path, name: `read${RLO}dm.sh`, size: 4 }]}
      selectedPath={null}
      expanded={new Set()}
      onToggle={() => {}}
      onSelect={() => {}}
      renderChildren={() => null}
    />,
  );
  const button = screen.getByRole("button", { name: "read⟨U+202E⟩dm.sh 4 B (p/read⟨U+202E⟩dm.sh)" });
  expect(button).toHaveTextContent("read⟨U+202E⟩ (right-to-left override)dm.sh");
  expectNoRawControl(tree.container);
  tree.unmount();

  const location = render(
    <MemoryRouter>
      <LocationHrefContext.Provider value={(at) => `/files?path=${encodeURIComponent(at)}`}>
        <FindingLocation location={`${path}:3`} />
      </LocationHrefContext.Provider>
    </MemoryRouter>,
  );
  const link = screen.getByRole("link");
  expect(link).toHaveTextContent("p/read⟨U+202E⟩ (right-to-left override)dm.sh:3");
  // The address still names the file as written.
  expect(link).toHaveAttribute("href", `/files?path=${encodeURIComponent(`${path}:3`)}`);
  expectNoRawControl(location.container);
});

/**
 * @SVCs SVC_GW_APPROVAL_0043
 */
test("selecting_a_file_whose_name_holds_a_control_passes_the_path_as_written", async () => {
  const user = userEvent.setup();
  const onSelect = vi.fn();
  const path = `p/read${RLO}dm.sh`;
  render(
    <SnapshotFileTree
      entries={[{ kind: "file", path, name: `read${RLO}dm.sh`, size: 4 }]}
      selectedPath={null}
      expanded={new Set()}
      onToggle={() => {}}
      onSelect={onSelect}
      renderChildren={() => null}
    />,
  );

  await user.click(screen.getByRole("button", { name: /^read⟨U\+202E⟩dm\.sh/ }));
  expect(onSelect).toHaveBeenCalledWith(path);
});

/**
 * @SVCs SVC_GW_APPROVAL_0041
 */
test("the_source_view_names_its_file_with_the_token", () => {
  render(<SourceView path={`p/read${RLO}dm.sh`} text="echo" findings={[]} />);

  expect(screen.getByRole("list", { name: "Lines of p/read⟨U+202E⟩dm.sh" })).toBeInTheDocument();
});

/**
 * @SVCs SVC_GW_APPROVAL_0041
 */
test("an_external_plugin_name_and_clone_url_show_their_controls_as_tokens", async () => {
  const member = compositeProvenance.closure!.members![0]!;
  server.use(
    http.get("/api/v1/snapshots/:id/provenance", () =>
      HttpResponse.json({
        ...compositeProvenance,
        closure: {
          ...compositeProvenance.closure!,
          members: [{ ...member, pluginName: `to${RLO}ols`, cloneUrl: `https://example.test/to${RLO}ols` }],
        },
      }),
    ),
  );
  const { container } = render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ProvenanceDetails snapshotId={compositeProvenance.snapshotId!} />
    </QueryClientProvider>,
  );

  const sources = await screen.findByRole("region", { name: "External plugin sources" });
  expect(within(sources).getByText("to", { exact: false, selector: ".font-medium" })).toHaveTextContent(
    "to⟨U+202E⟩ (right-to-left override)ols",
  );
  expect(sources).toHaveTextContent("https://example.test/to⟨U+202E⟩ (right-to-left override)ols");
  expectNoRawControl(container);
});
