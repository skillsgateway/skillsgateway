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

/**
 * Retroactively quarantined by a re-vetting violation: approved once, then revoked, so it carries
 * both an approval record and a revocation record at the same time (GW_0050).
 */
export const revokedSnapshot: Schemas["Snapshot"] = {
  id: 3,
  marketplaceId: 1,
  sha: "9999888877776666555544443333222211110000",
  state: "revoked",
  violation: "re-vetting violation: [secret-scan]",
  createdAt: "2026-08-09T10:00:00Z",
  decidedBy: "alice",
  decidedAt: "2026-08-09T11:00:00Z",
  revokedAt: "2026-08-15T09:00:00Z",
  revokedBy: "revet-policy",
};

/**
 * The default answer of the cooling-off gate: eligible, which is what every deployment sees until
 * an operator configures a minimum release age (GW_0073). Tests that need the window shut override
 * this handler with {@link tooYoung}.
 */
export const eligible: Schemas["Eligibility"] = {
  snapshotId: 1,
  eligible: true,
  firstIngestedAt: "2026-08-14T10:00:00Z",
  eligibleAt: "2026-08-14T10:00:00Z",
  ageSeconds: 900000,
  remainingSeconds: 0,
  minimumReleaseAgeSeconds: 0,
};

/** Inside the window: 2d 4h still to wait on a 3d minimum. */
export const tooYoung: Schemas["Eligibility"] = {
  snapshotId: 1,
  eligible: false,
  firstIngestedAt: "2026-08-14T10:00:00Z",
  eligibleAt: "2026-08-17T10:00:00Z",
  ageSeconds: 57600,
  remainingSeconds: 187200,
  minimumReleaseAgeSeconds: 259200,
};

export const fetchers: Schemas["Fetcher"][] = [
  { principal: "team-payments", fetches: 12, lastFetch: "2026-08-14T22:10:00Z" },
  { principal: "ci-runner", fetches: 3, lastFetch: "2026-08-13T06:00:00Z" },
];

export const marketplace: Schemas["MarketplaceView"] = {
  id: 1,
  name: "corp-marketplace",
  url: "https://github.com/corp/marketplace.git",
  createdAt: "2026-08-14T09:00:00Z",
  snapshots: [heldSnapshot, deletedSnapshot, revokedSnapshot],
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

/** A blocked chain run: one connector failed, one passed — the reviewer's evidence. */
export const blockedVetting: Schemas["VettingView"] = {
  snapshotId: 1,
  outcome: "BLOCKED",
  recordedOutcome: "BLOCKED",
  suppressed: [],
  uncovered: [
    {
      connector: "secret-scan",
      ruleId: "aws-access-key-id",
      location: "plugins/hello/DEPLOY.md:5",
      severity: "CRITICAL",
      message: "an AWS access key id is committed in this file",
    },
  ],
  waivers: [],
  run: {
    runId: 5,
    snapshotId: 1,
    trigger: "ingestion",
    outcome: "BLOCKED",
    startedAt: "2026-08-14T10:00:00Z",
    finishedAt: "2026-08-14T10:00:01Z",
    verdicts: [
      {
        verdictId: 9,
        connector: "secret-scan",
        position: 0,
        state: "FAIL",
        detail: "1 finding(s); worst critical",
        findings: [
          {
            id: "aws-access-key-id",
            severity: "CRITICAL",
            location: "plugins/hello/DEPLOY.md:5",
            message: "an AWS access key id is committed in this file",
          },
        ],
      },
      {
        verdictId: 10,
        connector: "prompt-injection",
        position: 1,
        state: "PASS",
        findings: [],
      },
    ],
  },
  connectors: [
    { name: "secret-scan", order: 100, description: "Regex and entropy rules over text files." },
    { name: "prompt-injection", order: 200, description: "Pattern heuristics over instructions." },
  ],
};

/** The same run, once the blocking finding has been accepted: cleared, but visibly by a waiver. */
export const waivedVetting: Schemas["VettingView"] = {
  ...blockedVetting,
  outcome: "CLEAR_WITH_WAIVERS",
  recordedOutcome: "BLOCKED",
  uncovered: [],
  suppressed: [
    {
      connector: "secret-scan",
      ruleId: "aws-access-key-id",
      location: "plugins/hello/DEPLOY.md:5",
      waiverId: 3,
      approvedBy: "alice",
      expiresAt: "2026-09-14T23:59:59Z",
    },
  ],
  waivers: [
    {
      id: 3,
      marketplace: "corp-marketplace",
      ruleId: "aws-access-key-id",
      scope: "SNAPSHOT",
      scopeValue: "a1b2c3",
      justification: "documented dummy key in fixtures",
      approvedBy: "alice",
      createdAt: "2026-08-15T10:00:00Z",
      expiresAt: "2026-09-14T23:59:59Z",
      active: true,
    },
  ],
};

export const fileTree: Schemas["FileTree"] = {
  snapshotId: 1,
  sha: heldSnapshot.sha,
  truncated: false,
  entries: [
    { path: ".claude-plugin/marketplace.json", size: 180 },
    { path: "plugins/hello/skills/hello/SKILL.md", size: 120 },
    { path: "data/huge.txt", size: 900000 },
    { path: "assets/logo.bin", size: 4096 },
  ],
};

/** Hostile-shaped SKILL.md: the embedded HTML must render as text, never as markup. */
export const skillMarkdown =
  "# Hello skill\n\nA test skill that says hello.\n\n<img src=x onerror=alert(1)>\n\n```console\n$ echo hi\n```\n";

export const snapshotDiff: Schemas["SnapshotDiff"] = {
  snapshotId: 1,
  sha: heldSnapshot.sha,
  baselineSha: "1111222233334444555566667777888899990000",
  truncated: false,
  entries: [
    {
      path: "plugins/hello/skills/hello/SKILL.md",
      type: "modified",
      binary: false,
      truncated: false,
      diff: "--- a/plugins/hello/skills/hello/SKILL.md\n+++ b/plugins/hello/skills/hello/SKILL.md\n@@ -1 +1 @@\n-old instruction\n+new instruction\n",
    },
    { path: "docs/NEW.md", type: "added", binary: false, truncated: false, diff: "+# Brand new\n" },
    { path: "docs/OLD.md", type: "removed", binary: false, truncated: false, diff: "-# Old\n" },
  ],
};

function fileContent(path: string): Schemas["FileContent"] {
  if (path.endsWith(".bin")) {
    return { snapshotId: 1, path, size: 4096, binary: true, truncated: false };
  }
  if (path === "data/huge.txt") {
    return { snapshotId: 1, path, size: 900000, binary: false, truncated: true, text: "first part only" };
  }
  return { snapshotId: 1, path, size: 120, binary: false, truncated: false, text: skillMarkdown };
}

/** Adoption over the window: one serving marketplace, its tip plus a superseded SHA. */
export const adoptionEntry: Schemas["MarketplaceAdoption"] = {
  marketplace: "corp-marketplace",
  servedSha: "aaaabbbbccccddddeeeeffff0000111122223333",
  fetches: 14,
  identities: 3,
  lastFetch: "2026-08-14T22:10:00Z",
  snapshots: [
    {
      sha: "aaaabbbbccccddddeeeeffff0000111122223333",
      fetches: 9,
      identities: 3,
      lastFetch: "2026-08-14T22:10:00Z",
      current: true,
    },
    {
      sha: "1111222233334444555566667777888899990000",
      fetches: 5,
      identities: 2,
      lastFetch: "2026-08-12T08:00:00Z",
      current: false,
    },
  ],
};

export const marketplaceAdoption: Schemas["MarketplaceAdoption"][] = [adoptionEntry];

/** One identity behind the tip, one holding content of a marketplace no longer serving. */
export const staleIdentities: Schemas["StaleIdentity"][] = [
  {
    principal: "team-payments",
    marketplace: "corp-marketplace",
    sha: "1111222233334444555566667777888899990000",
    lastFetch: "2026-08-12T08:00:00Z",
    servedSha: "aaaabbbbccccddddeeeeffff0000111122223333",
  },
  {
    principal: "ci-runner",
    marketplace: "retired-marketplace",
    sha: "9999888877776666555544443333222211110000",
    lastFetch: "2026-08-10T06:00:00Z",
  },
];

export const handlers = [
  http.get("/api/adoption", () => HttpResponse.json(marketplaceAdoption)),
  http.get("/api/adoption/staleness", () => HttpResponse.json(staleIdentities)),
  http.get("/api/me", () =>
    HttpResponse.json({ username: "alice", rolesEnabled: false, roles: [], claimsTruncated: false }),
  ),
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
  http.get("/api/snapshots/:id/vetting", () => HttpResponse.json(blockedVetting)),
  http.post("/api/snapshots/:id/waivers", () =>
    HttpResponse.json<Schemas["WaiverView"]>(waivedVetting.waivers![0], { status: 201 }),
  ),
  http.get("/api/marketplaces/:name/waivers", () => HttpResponse.json(waivedVetting.waivers)),
  http.delete("/api/waivers/:id", () =>
    HttpResponse.json<Schemas["WaiverView"]>({ ...waivedVetting.waivers![0], active: false }),
  ),
  http.post("/api/snapshots/:id/approve", () =>
    HttpResponse.json<Schemas["Snapshot"]>({ ...heldSnapshot, state: "approved", decidedBy: "alice" }),
  ),
  http.post("/api/snapshots/:id/revet", () =>
    HttpResponse.json<Schemas["RevetResult"]>({
      snapshotId: 1,
      marketplace: "corp-marketplace",
      sha: heldSnapshot.sha,
      runId: 9,
      classification: "CLEAR",
      outcome: "CLEAR",
      revoked: false,
      mode: "WARN",
      uncovered: [],
      affected: [],
    }),
  ),
  http.get("/api/snapshots/:id/release-age", () => HttpResponse.json(eligible)),
  http.get("/api/snapshots/:id/fetchers", () => HttpResponse.json(fetchers)),
  http.get("/api/snapshots/:id/files", () => HttpResponse.json(fileTree)),
  http.get("/api/snapshots/:id/file", ({ request }) =>
    HttpResponse.json(fileContent(new URL(request.url).searchParams.get("path") ?? "")),
  ),
  http.get("/api/snapshots/:id/diff", () => HttpResponse.json(snapshotDiff)),
  http.get("/api/tokens", () => HttpResponse.json<Schemas["TokenView"][]>([])),
  http.post("/api/tokens", () => HttpResponse.json(issuedToken, { status: 201 })),
  http.get("/api/audit", () => HttpResponse.json([])),
  http.get("/api/audit/sinks", () => HttpResponse.json<Schemas["SinkView"][]>([auditSink])),
  http.post("/api/audit/sinks", () => HttpResponse.json(createdAuditSink, { status: 201 })),
  http.get("/api/webhooks", () => HttpResponse.json<Schemas["SubscriberView"][]>([subscriber])),
  http.get("/api/webhooks/events", () =>
    HttpResponse.json(["snapshot.ingested", "snapshot.approved", "snapshot.rejected", "snapshot.revoked"]),
  ),
  http.get("/api/webhooks/deliveries", () => HttpResponse.json<Schemas["WebhookDelivery"][]>([delivery])),
  http.post("/api/webhooks", () => HttpResponse.json(createdSubscriber, { status: 201 })),
];
