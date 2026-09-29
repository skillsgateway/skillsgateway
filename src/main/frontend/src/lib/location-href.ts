import { createContext } from "react";

/**
 * Where a finding's location leads, when it leads anywhere (GW_APPROVAL_0030). The review card
 * provides one that opens the file at its line in the Contents tab; a surface that must not
 * navigate away (the approval dialog) provides none, and its locations stay text.
 */
export const LocationHrefContext = createContext<((location: string) => string | null) | null>(null);
