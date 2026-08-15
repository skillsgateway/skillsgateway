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

/** Soft-deleted by retention: still listed, still rejected, restorable until purgeAfter. */
export const deletedSnapshot: Schemas["Snapshot"] = {
  id: 2,
  marketplaceId: 1,
  sha: "1111222233334444555566667777888899990000",
  state: "rejected",
  createdAt: "2026-08-10T10:00:00Z",
  deletedAt: "2026-08-14T11:00:00Z",
  deletedReason: "held-too-long",
  purgeAfter: "2026-08-28T11:00:00Z",
};

export const marketplace: Schemas["MarketplaceView"] = {
  id: 1,
  name: "corp-marketplace",
  url: "https://github.com/corp/marketplace.git",
  createdAt: "2026-08-14T09:00:00Z",
  snapshots: [heldSnapshot, deletedSnapshot],
};

export const issuedToken: Schemas["IssuedToken"] = {
  id: 7,
  name: "ci-runner",
  token: "sgw_cleartext_shown_once",
  createdAt: "2026-08-14T10:00:00Z",
};

export const subscriber: Schemas["SubscriberView"] = {
  id: 3,
  name: "ci-bot",
  url: "https://ci.example.com/hooks/skills-gateway",
  events: "snapshot.approved",
  enabled: true,
  createdAt: "2026-08-14T10:00:00Z",
};

export const delivery: Schemas["WebhookDelivery"] = {
  id: 11,
  subscriberId: 3,
  event: "snapshot.approved",
  payload: '{"event":"snapshot.approved"}',
  state: "delivered",
  attempts: 1,
  nextAttemptAt: "2026-08-14T10:00:00Z",
  lastStatus: 200,
  createdAt: "2026-08-14T10:00:00Z",
  updatedAt: "2026-08-14T10:00:01Z",
};

export const createdSubscriber: Schemas["CreatedSubscriber"] = {
  id: 4,
  name: "new-bot",
  url: "https://ci.example.com/hooks/skills-gateway",
  events: "*",
  secret: "whsec_shown_once",
  createdAt: "2026-08-14T10:00:00Z",
};

export const auditSink: Schemas["SinkView"] = {
  id: 2,
  name: "siem",
  kind: "webhook",
  url: "https://siem.example.com/ingest/skills-gateway",
  cursorPosition: 42,
  ledgerHead: 45,
  behind: 3,
  batchSize: 500,
  enabled: true,
  createdAt: "2026-08-14T10:00:00Z",
};

export const createdAuditSink: Schemas["CreatedSink"] = {
  id: 3,
  name: "new-siem",
  kind: "webhook",
  url: "https://siem.example.com/ingest/skills-gateway",
  cursorPosition: 0,
  batchSize: 500,
  secret: "whsec_sink_shown_once",
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
  http.delete("/api/snapshots/:id", () =>
    HttpResponse.json<Schemas["Snapshot"]>({
      ...heldSnapshot,
      deletedAt: "2026-08-14T12:00:00Z",
      deletedReason: "manual",
      purgeAfter: "2026-08-28T12:00:00Z",
    }),
  ),
  http.post("/api/snapshots/:id/restore", () => HttpResponse.json(heldSnapshot)),
  http.get("/api/tokens", () => HttpResponse.json<Schemas["TokenView"][]>([])),
  http.post("/api/tokens", () => HttpResponse.json(issuedToken, { status: 201 })),
  http.get("/api/audit", () => HttpResponse.json([])),
  http.get("/api/audit/sinks", () => HttpResponse.json<Schemas["SinkView"][]>([auditSink])),
  http.post("/api/audit/sinks", () => HttpResponse.json(createdAuditSink, { status: 201 })),
  http.get("/api/webhooks", () => HttpResponse.json<Schemas["SubscriberView"][]>([subscriber])),
  http.get("/api/webhooks/deliveries", () => HttpResponse.json<Schemas["WebhookDelivery"][]>([delivery])),
  http.post("/api/webhooks", () => HttpResponse.json(createdSubscriber, { status: 201 })),
];
