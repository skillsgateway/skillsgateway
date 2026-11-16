import { useId, useState } from "react";
import { useCatalogName, useMarketplaces } from "@/api/queries";
import { SegmentedGroup } from "@/components/segmented-group";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { SCOPE_CHOICES, sortedNames, type ScopeChoice } from "@/lib/token-scope";

/** Rows drawn at once; past this the list says so and the filter is how to reach the rest. */
const ROWS_SHOWN = 200;

export type ScopeSelection = { choice: ScopeChoice; chosen: string[] };

/**
 * The marketplace choice of a personal access token: every marketplace (the default, as the
 * API's), or the marketplaces the user ticks. The registered marketplaces are what any signed-in
 * session can already read, so a session with no role sees the same list, and a token can only be
 * narrowed by this, never widened. The virtual catalog is offered by the name the gateway
 * reports, once it is serving one.
 *
 * A filter and a bounded, scrolling list cope with many marketplaces; whatever is ticked stays
 * in the summary line, so a choice hidden by the filter is never invisible.
 *
 * Names are not checked here against the list read: the server's registry is authoritative, and a
 * marketplace removed after the list was read is refused there, as the API states it. The one
 * rule mirrored is the one the server cannot enforce: it reads an empty list as every marketplace.
 *
 * @Requirements GW_AUTH_0079
 */
export function MarketplaceScopePicker({
  value,
  onChange,
  starting,
}: {
  value: ScopeSelection;
  onChange: (next: ScopeSelection) => void;
  /** Ticked when the user first switches to the chosen list: the marketplace they came from. */
  starting?: string;
}) {
  const marketplaces = useMarketplaces();
  const catalog = useCatalogName();
  const [filter, setFilter] = useState("");
  const hintId = useId();
  const filterId = useId();

  const names = sortedNames(
    (marketplaces.data ?? []).flatMap((marketplace) => (marketplace.name ? [marketplace.name] : [])),
  );
  const catalogName = catalog.data ?? null;
  // A chosen name the list no longer holds (a marketplace removed since it was ticked) stays a row,
  // so it can be unticked; the server refuses it if it is sent (design D4).
  const offered = sortedNames([
    ...new Set([...names, ...(catalogName ? [catalogName] : []), ...value.chosen]),
  ]);
  const needle = filter.trim().toLowerCase();
  const matching = needle ? offered.filter((name) => name.toLowerCase().includes(needle)) : offered;
  const shown = matching.slice(0, ROWS_SHOWN);
  const chosen = new Set(value.chosen);

  const setChoice = (choice: ScopeChoice) => {
    const seed = choice === "chosen" && value.chosen.length === 0 && starting ? [starting] : value.chosen;
    onChange({ choice, chosen: seed });
  };
  const toggle = (name: string, on: boolean) => {
    const next = new Set(chosen);
    if (on) next.add(name);
    else next.delete(name);
    onChange({ choice: value.choice, chosen: sortedNames([...next]) });
  };

  return (
    <div className="space-y-2">
      <SegmentedGroup
        label="Marketplaces"
        value={value.choice}
        options={SCOPE_CHOICES}
        onChange={setChoice}
        describedBy={hintId}
      />
      <p id={hintId} className="text-xs text-muted-foreground">
        {value.choice === "every"
          ? "The token fetches every marketplace this gateway serves you. Choose marketplaces to give it less."
          : value.chosen.length === 0
            ? "Choose at least one marketplace: a token that names none would fetch every marketplace."
            : "The token fetches only these; any other marketplace is answered as if it did not exist."}
      </p>
      {value.choice === "chosen" ? (
        <div className="space-y-2 rounded-md border p-3">
          {marketplaces.isLoading ? <p className="text-xs text-muted-foreground">Loading marketplaces…</p> : null}
          {marketplaces.isError ? (
            <div role="alert" className="flex flex-wrap items-center gap-2 text-xs text-destructive">
              <span className="min-w-0 break-words">{marketplaces.error.message}</span>
              <Button type="button" size="sm" variant="outline" onClick={() => void marketplaces.refetch()}>
                Try again
              </Button>
            </div>
          ) : null}
          <label htmlFor={filterId} className="sr-only">
            Filter marketplaces
          </label>
          <Input
            id={filterId}
            value={filter}
            onChange={(event) => setFilter(event.target.value)}
            placeholder="Filter marketplaces"
            autoComplete="off"
          />
          <div className="max-h-48 space-y-1 overflow-y-auto" role="group" aria-label="Marketplaces to include">
            {shown.map((name) => (
              <label key={name} className="flex items-start gap-2 text-sm">
                <Checkbox
                  aria-label={name}
                  checked={chosen.has(name)}
                  onCheckedChange={(on) => toggle(name, Boolean(on))}
                />
                <span className="min-w-0 break-all">
                  <span className="font-mono text-xs">{name}</span>
                  {name === catalogName ? (
                    <span className="block text-xs text-muted-foreground">
                      The catalog: every served marketplace in one repository, fetched as '{name}'.
                    </span>
                  ) : null}
                </span>
              </label>
            ))}
            {matching.length > shown.length ? (
              <p className="text-xs text-muted-foreground">
                Showing the first {shown.length} of {matching.length}: type in the filter to narrow the list.
              </p>
            ) : null}
            {!marketplaces.isLoading && shown.length === 0 ? (
              <p className="text-xs text-muted-foreground">
                {needle ? `No marketplace matches '${filter.trim()}'.` : "No marketplaces are registered."}
              </p>
            ) : null}
          </div>
          <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
            <span data-testid="chosen-summary" role="status" className="min-w-0 break-all">
              {value.chosen.length === 0
                ? "None chosen."
                : `${value.chosen.length} chosen: ${sortedNames(value.chosen).join(", ")}.`}
            </span>
            {value.chosen.length > 0 ? (
              <Button type="button" size="sm" variant="ghost" onClick={() => onChange({ choice: "chosen", chosen: [] })}>
                Clear
              </Button>
            ) : null}
          </div>
        </div>
      ) : null}
    </div>
  );
}
