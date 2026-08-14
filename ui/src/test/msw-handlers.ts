/**
 * Contract-derived MSW handlers: response shapes are the generated OpenAPI types
 * (src/api/types.gen.ts), so a backend contract change breaks compilation here.
 * Used in vitest component tests and dev/Storybook only — never in the acceptance
 * path (ADR 0003); acceptance is Playwright against the real gateway.
 */
import { http, HttpResponse } from "msw";
import type { components } from "@/api/types.gen";

type Schemas = components["schemas"];

export const heldSnapshot: Schemas["Snapshot"] = {
  id: 1,
  marketplaceId: 1,
  sha: "aaaabbbbccccddddeeeeffff0000111122223333",
  state: "held",
  createdAt: "2026-08-14T10:00:00Z",
};

export const marketplace: Schemas["MarketplaceView"] = {
  id: 1,
  name: "corp-marketplace",
  url: "https://github.com/corp/marketplace.git",
  createdAt: "2026-08-14T09:00:00Z",
  snapshots: [heldSnapshot],
};

export const issuedToken: Schemas["IssuedToken"] = {
  id: 7,
  name: "ci-runner",
  token: "sgw_cleartext_shown_once",
  createdAt: "2026-08-14T10:00:00Z",
};

export const handlers = [
  http.get("/api/me", () => HttpResponse.json({ username: "alice" })),
  http.get("/api/marketplaces", () => HttpResponse.json([marketplace])),
  http.post("/api/marketplaces", () =>
    HttpResponse.json<Schemas["Marketplace"]>(
      { id: 2, name: "new-marketplace", url: "https://example.com/m.git" },
      { status: 201 },
    ),
  ),
  http.get("/api/tokens", () => HttpResponse.json<Schemas["TokenView"][]>([])),
  http.post("/api/tokens", () => HttpResponse.json(issuedToken, { status: 201 })),
  http.get("/api/audit", () => HttpResponse.json([])),
];
