import { createContext, useContext } from "react";

/** What the session may do to the snapshots of the marketplace on screen. */
export type MarketplaceRights = {
  /** The marketplace the rights are for; null outside a marketplace page. */
  marketplace: string | null;
  /** Ingest, approve, reject, waive and re-vet: an approver of this marketplace, or an admin. */
  decide: boolean;
  /** Delete and restore a snapshot: an admin. */
  administer: boolean;
};

/**
 * Provided by the marketplace page from the session's roles (GW_AUTH_0064). Outside one — a story
 * or a test of one component — nothing is narrowed, which is what those surfaces showed before.
 */
export const MarketplaceRightsContext = createContext<MarketplaceRights>({
  marketplace: null,
  decide: true,
  administer: true,
});

export function useMarketplaceRights(): MarketplaceRights {
  return useContext(MarketplaceRightsContext);
}
