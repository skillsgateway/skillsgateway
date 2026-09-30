import { Button } from "@/components/ui/button";

/**
 * The control that reaches past the ledger page already loaded: it passes the last page's
 * `nextBefore` back for the next older one, and says so when the oldest entry held is in hand.
 *
 * @Requirements GW_AUDIT_0008
 */
export function LoadOlderEntries({
  loaded,
  hasMore,
  loading,
  onLoad,
}: {
  loaded: number;
  hasMore: boolean;
  loading: boolean;
  onLoad: () => void;
}) {
  const noun = loaded === 1 ? "entry" : "entries";
  return (
    <div className="flex flex-wrap items-center gap-3 text-xs text-muted-foreground">
      {hasMore ? (
        <Button size="sm" variant="outline" disabled={loading} onClick={onLoad}>
          {loading ? "Loading…" : "Load older entries"}
        </Button>
      ) : null}
      <span>
        {hasMore
          ? `${loaded} ${noun} loaded, newest first.`
          : `${loaded} ${noun} loaded — nothing older is recorded.`}
      </span>
    </div>
  );
}
