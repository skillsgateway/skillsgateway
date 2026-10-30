import { useEffect } from "react";
import { useLocation, useNavigate } from "react-router-dom";

const PRODUCT = "Skills Gateway";

/** The section of a marketplace each address names; the marketplace's own address is its review. */
const MARKETPLACE_SECTION: Record<string, string> = {
  "": "Review",
  snapshots: "Snapshots",
  activity: "Activity",
  settings: "Settings",
};

/**
 * The tab title for a portal address: the page, then its marketplace where it has one, then the
 * product, so several open tabs can be told apart by their first words. `fallback` names an
 * estate-wide page (the shell's breadcrumb already knows them).
 */
export function pageTitle(pathname: string, fallback: string): string {
  const files = /^\/marketplaces\/([^/]+)\/snapshots\/[^/]+\/files$/.exec(pathname);
  const section = /^\/marketplaces\/([^/]+)(?:\/([^/]+))?\/?$/.exec(pathname);
  let page = fallback;
  if (files) page = `Snapshot contents · ${decodeURIComponent(files[1]!)}`;
  else if (section && MARKETPLACE_SECTION[section[2] ?? ""] !== undefined) {
    page = `${MARKETPLACE_SECTION[section[2] ?? ""]} · ${decodeURIComponent(section[1]!)}`;
  }
  return page ? `${page} · ${PRODUCT}` : PRODUCT;
}

/** Keeps `document.title` in step with the page shown. */
export function useDocumentTitle(title: string) {
  useEffect(() => {
    document.title = title;
  }, [title]);
}

/** The parameter Spring Security's request cache appends to the page it returns to after sign-in. */
const SIGN_IN_MARKER = "continue";

/**
 * Removes the sign-in marker from the address once the portal is showing the page it returned
 * to, keeping every other parameter, so the address a reviewer copies is the one they were sent.
 * The server needs the marker only on the request that carried it, which has been answered.
 */
export function useStripSignInMarker() {
  const location = useLocation();
  const navigate = useNavigate();
  useEffect(() => {
    const params = new URLSearchParams(location.search);
    if (!params.has(SIGN_IN_MARKER)) return;
    params.delete(SIGN_IN_MARKER);
    const search = params.toString();
    void navigate(
      { pathname: location.pathname, search: search ? `?${search}` : "", hash: location.hash },
      { replace: true, state: location.state },
    );
  }, [location, navigate]);
}
