/**
 * Contract-derived MSW handlers: response shapes are the generated OpenAPI types
 * (src/api/types.gen.ts), so a backend contract change breaks compilation here.
 * Used in vitest component tests and dev/Storybook only — never in the acceptance
 * path (ADR 0003); acceptance is Playwright against the real gateway.
 */
import { http, HttpResponse } from "msw";
import type { components } from "@/api/types.gen";
import { countDiffLines } from "@/lib/snapshot-delta";

type Schemas = components["schemas"];

export const heldSnapshot: Schemas["Snapshot"] = {
  id: 1,
  marketplaceId: 1,
  sha: "aaaabbbbccccddddeeeeffff0000111122223333",
  state: "held",
  createdAt: "2026-08-14T10:00:00Z",
};

/** Provenance of a composite snapshot: the served commit differs from upstream, and the closure names why. */
export const compositeProvenance: Schemas["Provenance"] = {
  snapshotId: heldSnapshot.id,
  marketplace: "corp-marketplace",
  origin: "upstream",
  upstreamUrl: "https://github.com/corp/marketplace.git",
  upstreamSha: "3f9c2ab3f9c2ab3f9c2ab3f9c2ab3f9c2ab3f9c2",
  sha: heldSnapshot.sha,
  state: "held",
  ingestedAt: "2026-08-14T10:00:00Z",
  closure: {
    id: 7,
    snapshotId: heldSnapshot.id,
    digest: "4c2a".repeat(16),
    upstreamSha: "3f9c2ab3f9c2ab3f9c2ab3f9c2ab3f9c2ab3f9c2",
    transformerVersion: "1",
    createdAt: "2026-08-14T10:00:00Z",
    members: [
      {
        pluginName: "tools",
        sourceType: "github",
        declaredSource: "acme/tools",
        cloneUrl: "https://github.com/acme/tools",
        resolvedSha: "b7e0c1db7e0c1db7e0c1db7e0c1db7e0c1db7e0c",
        treeSha: "d41a9f2d41a9f2d41a9f2d41a9f2d41a9f2d41a9",
        graftPath: "_plugins/tools",
        objectCount: 12,
        inflatedBytes: 4096,
      },
    ],
  },
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
 * both an approval record and a revocation record at the same time (GW_VETTING_0013).
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
 * an operator configures a minimum release age (GW_APPROVAL_0004). Tests that need the window shut override
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

/**
 * Two tokens, because the Last used column has exactly two shapes worth seeing: one a client
 * authenticated with recently, and one that has never authenticated at all.
 */
export const tokenViews: Schemas["TokenView"][] = [
  {
    id: 7,
    name: "ci-runner",
    createdAt: "2026-08-14T10:00:00Z",
    scopes: [],
    pushScopes: [],
    sessionDerived: false,
    apiScopes: [],
    lastUsedAt: "2026-08-14T13:00:00Z",
  },
  // No lastUsedAt at all: nothing has ever authenticated with this one, which is the state the
  // column has to say something useful about.
  {
    id: 8,
    name: "spare-laptop",
    createdAt: "2026-08-14T10:00:00Z",
    scopes: [],
    pushScopes: [],
    sessionDerived: false,
    apiScopes: [],
  },
];

export const subscriber: Schemas["SubscriberView"] = {
  id: 3,
  name: "ci-bot",
  url: "https://ci.example.com/hooks/skills-gateway",
  events: ["marketplace.snapshot.approved"],
  enabled: true,
  createdAt: "2026-08-14T10:00:00Z",
};

export const delivery: Schemas["WebhookDelivery"] = {
  id: 11,
  subscriberId: 3,
  event: "marketplace.snapshot.approved",
  payload: '{"event":"marketplace.snapshot.approved"}',
  state: "delivered",
  attempts: 1,
  nextAttemptAt: "2026-08-14T10:00:00Z",
  lastStatus: 200,
  createdAt: "2026-08-14T10:00:00Z",
  updatedAt: "2026-08-14T10:00:01Z",
};

/**
 * The subscribable vocabulary and the shape each delivery carries. Typed from the contract, so
 * a field renamed in the payload fails typechecking here rather than at a receiver (ADR 0003).
 */
export const eventRegistry: Schemas["EventRegistry"] = {
  events: [
    "marketplace.snapshot.ingested",
    "marketplace.snapshot.approved",
    "marketplace.snapshot.rejected",
    "marketplace.snapshot.revoked",
    "marketplace.registered",
  ],
  examplePayload: {
    event: "marketplace.snapshot.approved",
    occurredAt: "2026-08-14T10:00:00Z",
    marketplace: "example-marketplace",
    snapshotId: 1,
    sha: "aaaabbbbccccddddeeeeffff0000111122223333",
    state: "approved",
    actor: "reviewer@example.com",
  },
  exampleApprovalPendingPayload: {
    event: "marketplace.snapshot.approval_pending",
    occurredAt: "2026-08-14T10:00:00Z",
    marketplace: "example-marketplace",
    snapshotId: 1,
    sha: "aaaabbbbccccddddeeeeffff0000111122223333",
    state: "held",
    actor: "scheduler",
    vetting: {
      runId: 1,
      outcome: "blocked",
      recordedOutcome: "blocked",
      blockingVetters: ["example-vetter"],
      uncoveredFindings: 1,
      waivedFindings: 0,
    },
  },
  exampleMarketplacePayload: {
    event: "marketplace.registered",
    occurredAt: "2026-08-14T10:00:00Z",
    marketplace: "example-marketplace",
    actor: "admin@example.com",
    detail: "origin=upstream",
  },
};

export const createdSubscriber: Schemas["CreatedSubscriber"] = {
  id: 4,
  name: "new-bot",
  url: "https://ci.example.com/hooks/skills-gateway",
  events: ["*"],
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

/** The git blob id the fixtures' findings are in. */
const DEPLOY_BLOB = "3b18e512dba79e4c8300dd08aeb37f8e728b8dad";
const VENDORED_BLOB = "8cda9ad203d3da62a297cbe080a926db44328715";

/** A blocked chain run: one vetter failed, one passed — the reviewer's evidence. */
export const blockedVetting: Schemas["VettingView"] = {
  snapshotId: 1,
  chainStaleness: {
    state: "IN_FORCE",
    runChain: "secret-scan@1,prompt-injection@1;mode=run-all",
    currentChain: "secret-scan@1,prompt-injection@1;mode=run-all",
  },
  outcome: "blocked",
  recordedOutcome: "blocked",
  suppressed: [],
  uncovered: [
    {
      vetter: "secret-scan",
      ruleId: "aws-access-key-id",
      location: "plugins/hello/DEPLOY.md:5",
      locations: ["plugins/hello/DEPLOY.md:5"],
      content: DEPLOY_BLOB,
      line: 5,
      severity: "critical",
      message: "an AWS access key id is committed in this file",
    },
  ],
  waivers: [],
  run: {
    runId: 5,
    snapshotId: 1,
    trigger: "ingestion",
    outcome: "blocked",
    startedAt: "2026-08-14T10:00:00Z",
    finishedAt: "2026-08-14T10:00:01Z",
    verdicts: [
      {
        verdictId: 9,
        vetter: "secret-scan",
        position: 0,
        state: "fail",
        detail: "1 finding(s); worst critical",
        findings: [
          {
            id: "aws-access-key-id",
            severity: "critical",
            location: "plugins/hello/DEPLOY.md:5",
            message: "an AWS access key id is committed in this file",
            content: DEPLOY_BLOB,
          },
        ],
        groups: [
          {
            ruleId: "aws-access-key-id",
            severity: "critical",
            message: "an AWS access key id is committed in this file",
            content: DEPLOY_BLOB,
            line: 5,
            locations: ["plugins/hello/DEPLOY.md:5"],
          },
        ],
      },
      {
        verdictId: 10,
        vetter: "prompt-injection",
        position: 1,
        state: "pass",
        findings: [],
      },
    ],
  },
  vetters: [
    {
      name: "secret-scan",
      order: 100,
      description: "Regex and entropy rules over text files.",
      version: "3",
      external: false,
    },
    {
      name: "prompt-injection",
      order: 200,
      description: "Pattern heuristics over instructions.",
      version: "1",
      external: false,
    },
  ],
};

const concealment = "the instructions tell the agent to conceal its actions from the user or reviewer";

/**
 * One instruction file vendored into three plugins, and one file of its own: two groups, the
 * first standing for three locations of identical content (GW_VETTING_0041).
 */
export const vendoredVetting: Schemas["VettingView"] = {
  ...blockedVetting,
  uncovered: [
    {
      vetter: "prompt-injection",
      ruleId: "concealment-instruction",
      location: "plugins/a/skills/x/SKILL.md:12",
      locations: [
        "plugins/a/skills/x/SKILL.md:12",
        "plugins/b/skills/x/SKILL.md:12",
        "plugins/c/skills/x/SKILL.md:12",
        "plugins/d/skills/x/SKILL.md:12",
      ],
      content: VENDORED_BLOB,
      line: 12,
      severity: "high",
      message: concealment,
    },
    {
      vetter: "prompt-injection",
      ruleId: "concealment-instruction",
      location: "plugins/e/skills/y/SKILL.md:4",
      locations: ["plugins/e/skills/y/SKILL.md:4"],
      content: DEPLOY_BLOB,
      line: 4,
      severity: "high",
      message: concealment,
    },
  ],
  run: {
    ...blockedVetting.run,
    verdicts: [
      {
        verdictId: 9,
        vetter: "secret-scan",
        position: 0,
        state: "pass",
        detail: "scanned 40 text file(s); 2 file(s) not scanned (2 over the size limit); applied 7 secret-shape rules",
        findings: [],
        groups: [
          {
            ruleId: "file-not-scanned",
            severity: "info",
            message: "2 file(s) not scanned: over the scan size limit: assets/demo.mp4, assets/hero.png",
            locations: [],
          },
        ],
      },
      {
        verdictId: 10,
        vetter: "prompt-injection",
        position: 1,
        state: "fail",
        detail: "5 finding(s); worst high",
        findings: [],
        groups: [
          {
            ruleId: "concealment-instruction",
            severity: "high",
            message: concealment,
            content: VENDORED_BLOB,
            line: 12,
            locations: [
              "plugins/a/skills/x/SKILL.md:12",
              "plugins/b/skills/x/SKILL.md:12",
              "plugins/c/skills/x/SKILL.md:12",
              "plugins/d/skills/x/SKILL.md:12",
            ],
          },
          {
            ruleId: "concealment-instruction",
            severity: "high",
            message: concealment,
            content: DEPLOY_BLOB,
            line: 4,
            locations: ["plugins/e/skills/y/SKILL.md:4"],
          },
        ],
      },
    ],
  },
};

/**
 * The same evidence, produced by a chain this marketplace no longer runs (GW_VETTING_0038): a
 * vetter was switched off after the run, so the run's description and the one in force differ.
 */
export const supersededChainVetting: Schemas["VettingView"] = {
  ...blockedVetting,
  chainStaleness: {
    state: "SUPERSEDED",
    runChain: "secret-scan@1,prompt-injection@1;mode=run-all",
    currentChain: "secret-scan@1,prompt-injection@1;mode=run-all;disabled=[secret-scan]",
  },
};

/** A run recorded before the chain identity was stamped: unknown, and not the same as current. */
export const undeterminedChainVetting: Schemas["VettingView"] = {
  ...blockedVetting,
  chainStaleness: {
    state: "UNDETERMINED",
    runChain: undefined,
    currentChain: "secret-scan@1,prompt-injection@1;mode=run-all",
  },
};

/** A clean run: every vetter passed and nothing is waiting on a waiver. */
export const clearVetting: Schemas["VettingView"] = {
  ...blockedVetting,
  outcome: "clear",
  recordedOutcome: "clear",
  uncovered: [],
  run: {
    ...blockedVetting.run!,
    outcome: "clear",
    verdicts: [
      {
        verdictId: 9,
        vetter: "secret-scan",
        position: 0,
        state: "pass",
        detail: "scanned 42 text files",
        findings: [],
      },
      {
        verdictId: 10,
        vetter: "prompt-injection",
        position: 1,
        state: "pass",
        detail: "scanned 3 skill instructions",
        findings: [],
      },
    ],
  },
};

/**
 * A chain with a vetter an administrator switched off and an external one that has not answered
 * yet: the two states that are neither a pass nor a failure, and that the flow has to word as
 * absences rather than conclusions.
 */
export const disabledAndPendingVetting: Schemas["VettingView"] = {
  ...blockedVetting,
  outcome: "blocked",
  recordedOutcome: "blocked",
  uncovered: [],
  run: {
    ...blockedVetting.run!,
    verdicts: [
      {
        verdictId: 11,
        vetter: "secret-scan",
        position: 0,
        state: "disabled",
        detail: "for marketplace 'corp-marketplace'",
        findings: [],
      },
      {
        verdictId: 12,
        vetter: "prompt-injection",
        position: 1,
        state: "pass",
        detail: "scanned 3 skill instructions",
        findings: [],
      },
      {
        verdictId: 13,
        vetter: "corp-llm-reviewer",
        position: 2,
        state: "pending",
        detail: "triggered; awaiting the reviewer's callback",
        findings: [],
      },
    ],
  },
  vetters: [
    ...blockedVetting.vetters!,
    {
      name: "corp-llm-reviewer",
      order: 300,
      description: "An operator-configured external reviewer.",
      version: "2026-09-01",
      external: true,
    },
  ],
};

/**
 * A run the chain stopped after the first failure: one FAIL, and the vetters after it recorded as
 * never reached. The outcome is blocked and stays blocked until the chain is run again.
 */
export const shortCircuitedVetting: Schemas["VettingView"] = {
  ...blockedVetting,
  outcome: "blocked",
  recordedOutcome: "blocked",
  run: {
    ...blockedVetting.run!,
    chain: "secret-scan@3,prompt-injection@1,license-scan@1;mode=stop-after-fail",
    verdicts: [
      ...blockedVetting.run!.verdicts!.slice(0, 1),
      {
        verdictId: 31,
        vetter: "prompt-injection",
        position: 1,
        state: "not_reached",
        detail: "not run: the chain stopped at 'secret-scan'",
        findings: [
          {
            id: "vetter-not-reached",
            severity: "info",
            location: "prompt-injection",
            message: "the chain stopped at 'secret-scan' and did not reach vetter 'prompt-injection'",
          },
        ],
      },
      {
        verdictId: 32,
        vetter: "license-scan",
        position: 2,
        state: "not_reached",
        detail: "not run: the chain stopped at 'secret-scan'",
        findings: [
          {
            id: "vetter-not-reached",
            severity: "info",
            location: "license-scan",
            message: "the chain stopped at 'secret-scan' and did not reach vetter 'license-scan'",
          },
        ],
      },
    ],
  },
  vetters: [
    ...blockedVetting.vetters!.slice(0, 1),
    { name: "prompt-injection", order: 200, description: "Pattern heuristics over instructions.", version: "1" },
    { name: "license-scan", order: 300, description: "SPDX headers.", version: "1" },
  ],
};

/** A marketplace running every vetter in its configured order, with nothing set anywhere. */
export const chainSettingsDefault: Schemas["ChainSettingsView"] = {
  mode: "run-all",
  modeSource: "default",
  order: ["secret-scan", "prompt-injection", "license-scan"],
  orderOverride: [],
  orderSource: "default",
};

/** The same marketplace, stopped early and reordered by an administrator. */
export const chainSettingsStopping: Schemas["ChainSettingsView"] = {
  mode: "stop-after-fail",
  modeSource: "marketplace",
  modeReason: "the external reviewer is billed per call",
  modeUpdatedBy: "alice",
  modeUpdatedAt: "2026-09-10T09:00:00Z",
  order: ["prompt-injection", "secret-scan", "license-scan"],
  orderOverride: ["prompt-injection", "secret-scan"],
  orderSource: "marketplace",
  orderReason: "cheapest first",
  orderUpdatedBy: "alice",
  orderUpdatedAt: "2026-09-10T09:01:00Z",
};

/** A marketplace's effective chain: one default, one global setting, one switched off here. */
export const marketplaceChain: Schemas["ChainVetterView"][] = [
  {
    name: "secret-scan",
    order: 100,
    description: "Regex and entropy rules over text files.",
    version: "3",
    external: false,
    enabled: false,
    source: "marketplace",
    reason: "vendor keys, expected in this marketplace",
    updatedBy: "alice",
    updatedAt: "2026-08-20T09:00:00Z",
  },
  {
    name: "prompt-injection",
    order: 200,
    description: "Pattern heuristics over instructions.",
    version: "1",
    external: false,
    enabled: true,
    source: "global",
    reason: "kept on across the estate",
    updatedBy: "root",
    updatedAt: "2026-08-01T09:00:00Z",
  },
  {
    name: "license-scan",
    order: 300,
    description: "Declared licences against the configured policy.",
    version: "1",
    external: false,
    enabled: true,
    source: "default",
  },
];

/**
 * The chain a marketplace with no override of its own runs. The source is never MARKETPLACE here,
 * which is the property the estate page's claim rests on.
 */
export const globalChain: Schemas["ChainVetterView"][] = [
  {
    name: "secret-scan",
    order: 100,
    description: "Regex and entropy rules over text files.",
    version: "3",
    external: false,
    enabled: true,
    source: "default",
  },
  {
    name: "prompt-injection",
    order: 200,
    description: "Pattern heuristics over instructions.",
    version: "1",
    external: false,
    enabled: true,
    source: "global",
    reason: "kept on across the estate",
    updatedBy: "root",
    updatedAt: "2026-08-01T09:00:00Z",
  },
  {
    name: "license-scan",
    order: 300,
    description: "Declared licences against the configured policy.",
    version: "1",
    external: false,
    enabled: true,
    source: "default",
  },
];

/** The default chain's mode and order, with nothing set anywhere. */
export const globalChainSettings: Schemas["ChainSettingsView"] = {
  mode: "run-all",
  modeSource: "default",
  order: ["secret-scan", "prompt-injection", "license-scan"],
  orderOverride: [],
  orderSource: "default",
};

/** Nothing in the estate departs from the default. */
export const noChainOverrides: Schemas["ChainSettings"] = { modes: [], orders: [] };

/** Two marketplaces that do: one pinned to a mode and an order, one with a vetter switched off. */
export const chainOverrides: Schemas["ChainSettings"] = {
  modes: [
    {
      id: 1,
      marketplaceId: 1,
      mode: "stop-after-fail",
      reason: "the external reviewer is billed per call",
      updatedBy: "alice",
      updatedAt: "2026-09-10T09:00:00Z",
    },
  ],
  orders: [
    {
      id: 1,
      marketplaceId: 1,
      vetters: ["prompt-injection", "secret-scan"],
      reason: "cheapest first",
      updatedBy: "alice",
      updatedAt: "2026-09-10T09:01:00Z",
    },
  ],
};

/** A per-marketplace vetter override, alongside a global one that is not a departure. */
export const vetterToggles: Schemas["VetterToggle"][] = [
  {
    id: 1,
    vetter: "prompt-injection",
    enabled: true,
    reason: "kept on across the estate",
    updatedBy: "root",
    updatedAt: "2026-08-01T09:00:00Z",
  },
  {
    id: 2,
    vetter: "secret-scan",
    marketplaceId: 2,
    enabled: false,
    reason: "vendor keys, expected here",
    updatedBy: "alice",
    updatedAt: "2026-08-20T09:00:00Z",
  },
];

/** Every named marketplace took the change. */
export const bulkApplied: Schemas["BulkChainResult"] = {
  correlationId: "1f0d9c24-0c4a-4a4f-9a2b-2b6f5a1f0c21",
  applied: 2,
  unchanged: 0,
  failed: 0,
  results: [
    { marketplace: "corp-marketplace", status: "applied", detail: "mode=stop-after-fail" },
    { marketplace: "partner-marketplace", status: "applied", detail: "mode=stop-after-fail" },
  ],
};

/** One of them did not, which the page must render as a failure rather than a footnote. */
export const bulkPartialFailure: Schemas["BulkChainResult"] = {
  correlationId: "8b4c1a90-6f21-4e0a-bb0d-2f0c7e4a91aa",
  applied: 1,
  unchanged: 0,
  failed: 1,
  results: [
    { marketplace: "corp-marketplace", status: "applied", detail: "mode=stop-after-fail" },
    {
      marketplace: "partner-marketplace",
      status: "failed",
      detail: "marketplace 'partner-marketplace' not found",
    },
  ],
};

/** The same run, once the blocking finding has been accepted: cleared, but visibly by a waiver. */
export const waivedVetting: Schemas["VettingView"] = {
  ...blockedVetting,
  outcome: "clear_with_waivers",
  recordedOutcome: "blocked",
  uncovered: [],
  suppressed: [
    {
      vetter: "secret-scan",
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
      scope: "snapshot",
      scopeValue: "a1b2c3",
      justification: "documented dummy key in fixtures",
      approvedBy: "alice",
      createdAt: "2026-08-15T10:00:00Z",
      expiresAt: "2026-09-14T23:59:59Z",
      active: true,
    },
  ],
};

export const snapshotContent: Schemas["SnapshotContent"] = {
  snapshotId: 1,
  sha: heldSnapshot.sha,
  state: "held",
  plugins: [
    {
      name: "hello",
      description: "greeting skills",
      source: "./plugins/hello",
      skills: [
        { name: "hello", path: "plugins/hello/skills/hello/SKILL.md" },
        { name: "greet", path: "plugins/hello/skills/greet/SKILL.md" },
      ],
    },
    {
      name: "review",
      description: "review skills",
      source: "./plugins/review",
      skills: [{ name: "critique", path: "plugins/review/skills/critique/SKILL.md" }],
      commands: [],
      agents: [
        { name: "asset-producer", path: "plugins/review/agents/asset-producer.md" },
        { name: "documenter", path: "plugins/review/agents/documenter.md" },
        { name: "finish-reviewer", path: "plugins/review/agents/finish-reviewer.md" },
        { name: "edit-applier", path: "plugins/review/agents/edit-applier.md" },
      ],
      hooks: [
        {
          event: "SessionStart",
          type: "command",
          runs: '"${CLAUDE_PLUGIN_ROOT}/scripts/engine" hook',
          location: "plugins/review/hooks/hooks.json:8",
          declaredBy: "plugin",
        },
        {
          event: "PostToolUse",
          matcher: "Edit|Write",
          type: "command",
          runs: '"${CLAUDE_PLUGIN_ROOT}/scripts/engine" hook',
          location: "plugins/review/hooks/hooks.json:21",
          declaredBy: "plugin",
        },
        {
          event: "Stop",
          type: "command",
          runs: '"${CLAUDE_PLUGIN_ROOT}/scripts/engine" hook',
          location: "plugins/review/hooks/hooks.json:33",
          declaredBy: "plugin",
        },
      ],
      mcpServers: [],
    },
  ],
};

/**
 * One of each status, so the panel's filtering is exercised: the unchanged skill must not appear
 * among the changes, and the plugin that lost its skills must appear even though the snapshot no
 * longer declares it.
 */
export const contentDiff: Schemas["ContentDiff"] = {
  snapshotId: 1,
  sha: heldSnapshot.sha,
  state: "held",
  baselineSnapshotId: 2,
  baselineSha: "1111222233334444555566667777888899990000",
  plugins: [
    {
      name: "hello",
      description: "greeting skills",
      source: "./plugins/hello",
      status: "changed",
      skills: [
        { name: "hello", path: "plugins/hello/skills/hello/SKILL.md", status: "unchanged" },
        { name: "greet", path: "plugins/hello/skills/greet/SKILL.md", status: "added" },
      ],
    },
    {
      name: "review",
      description: "review skills",
      source: "./plugins/review",
      status: "changed",
      skills: [
        {
          name: "critique",
          path: "plugins/review/skills/critique/SKILL.md",
          status: "moved",
          movedFromPlugin: "hello",
        },
        { name: "summarize", path: "plugins/review/skills/summarize/SKILL.md", status: "changed" },
      ],
    },
    {
      name: "legacy",
      description: "skills on their way out",
      source: "./plugins/legacy",
      status: "removed",
      skills: [{ name: "oldtool", path: "plugins/legacy/skills/oldtool/SKILL.md", status: "removed" }],
    },
  ],
  summary: { added: 1, removed: 1, changed: 1, moved: 1, unchanged: 1 },
};

export const fileTree: Schemas["FileTree"] = {
  snapshotId: 1,
  sha: heldSnapshot.sha,
  truncated: false,
  entries: [
    { path: ".claude-plugin/marketplace.json", size: 180 },
    { path: "plugins/hello/skills/hello/SKILL.md", size: 120 },
    { path: "docs/NEW.md", size: 14 },
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

/** A page of `items` from `offset`, with the paging fields the gateway sends beside it. */
function page<T>(items: readonly T[], offset: number, size: number) {
  const entries = items.slice(offset, offset + size);
  const end = offset + entries.length;
  const more = end < items.length;
  return { entries, total: items.length, truncated: more, ...(more ? { nextOffset: end } : {}) };
}

function offsetOf(request: Request): number {
  return Number(new URL(request.url).searchParams.get("offset") ?? "0");
}

/** `GET /files` over the fixture: an optional case-insensitive path search, paged at 2000. */
export function filesPage(tree: Schemas["FileTree"], request: Request): Schemas["FileTree"] {
  const needle = (new URL(request.url).searchParams.get("q") ?? "").trim().toLowerCase();
  const matches = (tree.entries ?? []).filter((entry) =>
    (entry.path ?? "").toLowerCase().includes(needle),
  );
  return { snapshotId: tree.snapshotId, sha: tree.sha, ...page(matches, offsetOf(request), 2000) };
}

/** `GET /diff` over the fixture: narrowed by `path` as a pathspec, paged at 500, with its summary. */
export function diffPage(diff: Schemas["SnapshotDiff"], request: Request): Schemas["SnapshotDiff"] {
  const path = new URL(request.url).searchParams.get("path");
  const entries = (diff.entries ?? []).filter(
    (entry) => !path || entry.path === path || (entry.path ?? "").startsWith(`${path}/`),
  );
  const count = (type: string) => entries.filter((entry) => entry.type === type).length;
  const lines = entries.map((entry) => countDiffLines(entry.diff ?? ""));
  return {
    snapshotId: diff.snapshotId,
    sha: diff.sha,
    baselineSha: diff.baselineSha,
    ...page(entries, offsetOf(request), 500),
    summary: {
      added: count("added"),
      modified: count("modified"),
      removed: count("removed"),
      binary: entries.filter((entry) => entry.binary).length,
      linesAdded: lines.reduce((sum, count) => sum + count.added, 0),
      linesRemoved: lines.reduce((sum, count) => sum + count.removed, 0),
    },
  };
}

/**
 * `GET /tree` over the fixture, the way the gateway assembles it: the snapshot's paths and the
 * diff's removed paths, grouped under `dir`, with statuses and per-directory counts.
 */
export function treePage(
  tree: Schemas["FileTree"],
  diff: Schemas["SnapshotDiff"],
  request: Request,
): Schemas["DirectoryListing"] | null {
  const dir = new URL(request.url).searchParams.get("dir") ?? "";
  const under = dir === "" ? "" : `${dir}/`;
  const status = new Map((diff.entries ?? []).map((entry) => [entry.path ?? "", entry.type]));
  const leaves = [
    ...(tree.entries ?? []).map((entry) => ({ path: entry.path ?? "", size: entry.size, present: true })),
    ...(diff.entries ?? [])
      .filter((entry) => entry.type === "removed")
      .map((entry) => ({ path: entry.path ?? "", size: undefined, present: false })),
  ].filter((leaf) => leaf.path.startsWith(under));
  if (dir !== "" && leaves.length === 0) return null;
  const children = new Map<string, Schemas["TreeChild"]>();
  for (const leaf of leaves) {
    const rest = leaf.path.slice(under.length);
    const [name = "", ...deeper] = rest.split("/");
    const changed = status.has(leaf.path) ? 1 : 0;
    if (deeper.length === 0) {
      children.set(`f:${name}`, {
        kind: "file",
        name,
        path: leaf.path,
        size: leaf.size,
        status: status.get(leaf.path),
      });
    } else {
      const current = children.get(`d:${name}`) ?? {
        kind: "directory",
        name,
        path: `${under}${name}`,
        files: 0,
        changed: 0,
      };
      current.files = (current.files ?? 0) + (leaf.present ? 1 : 0);
      current.changed = (current.changed ?? 0) + changed;
      children.set(`d:${name}`, current);
    }
  }
  const ordered = [...children.entries()]
    .sort(([a], [b]) => (a[0] === b[0] ? a.slice(2).localeCompare(b.slice(2)) : a[0] === "d" ? -1 : 1))
    .map(([, child]) => child);
  return {
    snapshotId: tree.snapshotId,
    sha: tree.sha,
    baselineSha: diff.baselineSha,
    dir,
    files: leaves.filter((leaf) => leaf.present).length,
    changed: leaves.filter((leaf) => status.has(leaf.path)).length,
    ...page(ordered, offsetOf(request), 500),
  };
}

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
  http.get("/api/v1/adoption", () => HttpResponse.json(marketplaceAdoption)),
  http.get("/api/v1/adoption/staleness", () => HttpResponse.json(staleIdentities)),
  http.get("/api/v1/me", () =>
    HttpResponse.json({ username: "alice", roles: [], claimsTruncated: false, version: "0.3.0" }),
  ),
  http.get("/api/v1/marketplaces", () => HttpResponse.json([marketplace])),
  http.post("/api/v1/marketplaces", () =>
    HttpResponse.json<Schemas["RegisteredMarketplace"]>(
      { id: 2, name: "new-marketplace", url: "https://example.com/m.git", warnings: [] },
      { status: 201 },
    ),
  ),
  http.delete("/api/v1/snapshots/:id", () =>
    HttpResponse.json<Schemas["Snapshot"]>({
      ...heldSnapshot,
      deletedAt: "2026-08-14T12:00:00Z",
      deletedReason: "manual",
      purgeAfter: "2026-08-28T12:00:00Z",
    }),
  ),
  http.post("/api/v1/snapshots/:id/restore", () => HttpResponse.json(heldSnapshot)),
  http.get("/api/v1/snapshots/:id/vetting", () => HttpResponse.json(blockedVetting)),
  http.post("/api/v1/snapshots/:id/waivers", () =>
    HttpResponse.json<Schemas["WaiverView"]>(waivedVetting.waivers![0], { status: 201 }),
  ),
  http.get("/api/v1/marketplaces/:name/waivers", () => HttpResponse.json(waivedVetting.waivers)),
  http.get("/api/v1/marketplaces/:name/vetting-chain", () => HttpResponse.json(marketplaceChain)),
  http.get("/api/v1/marketplaces/:name/vetting-chain-settings", () =>
    HttpResponse.json(chainSettingsDefault),
  ),
  http.get("/api/v1/vetting/global-chain", () => HttpResponse.json(globalChain)),
  http.get("/api/v1/vetting/global-chain-settings", () => HttpResponse.json(globalChainSettings)),
  http.get("/api/v1/vetting/chain-settings", () => HttpResponse.json(noChainOverrides)),
  http.get("/api/v1/vetting/vetter-toggles", () => HttpResponse.json<Schemas["VetterToggle"][]>([])),
  http.post("/api/v1/vetting/chain-settings/bulk", () => HttpResponse.json(bulkApplied)),
  http.put("/api/v1/vetting/chain-mode", () =>
    HttpResponse.json<Schemas["ChainModeSetting"]>({
      id: 1,
      marketplaceId: 1,
      mode: "stop-after-fail",
      updatedBy: "alice",
      updatedAt: "2026-09-10T09:00:00Z",
    }),
  ),
  http.put("/api/v1/vetting/chain-order", () =>
    HttpResponse.json<Schemas["ChainOrderSetting"]>({
      id: 1,
      marketplaceId: 1,
      vetters: ["prompt-injection", "secret-scan", "license-scan"],
      updatedBy: "alice",
      updatedAt: "2026-09-10T09:01:00Z",
    }),
  ),
  http.put("/api/v1/vetting/vetters/:name/toggle", ({ params }) =>
    HttpResponse.json<Schemas["VetterToggle"]>({
      id: 1,
      vetter: String(params.name),
      marketplaceId: 1,
      enabled: true,
      updatedBy: "alice",
      updatedAt: "2026-08-20T09:00:00Z",
    }),
  ),
  http.delete("/api/v1/waivers/:id", () =>
    HttpResponse.json<Schemas["WaiverView"]>({ ...waivedVetting.waivers![0], active: false }),
  ),
  http.post("/api/v1/snapshots/:id/approve", () =>
    HttpResponse.json<Schemas["Snapshot"]>({ ...heldSnapshot, state: "approved", decidedBy: "alice" }),
  ),
  http.post("/api/v1/snapshots/:id/revet", () =>
    HttpResponse.json<Schemas["RevetResult"]>({
      snapshotId: 1,
      marketplace: "corp-marketplace",
      sha: heldSnapshot.sha,
      runId: 9,
      classification: "clear",
      outcome: "clear",
      revoked: false,
      mode: "warn",
      uncovered: [],
      affected: [],
    }),
  ),
  http.get("/api/v1/snapshots/:id/release-age", () => HttpResponse.json(eligible)),
  // The default fixture is an independent reviewer: warn mode, nothing to declare. A story or a
  // test that wants the conflicted reviewer overrides this one handler.
  http.get("/api/v1/snapshots/:id/four-eyes", () =>
    HttpResponse.json({ mode: "warn", conflicts: [], refused: false }),
  ),
  // No plugin name of the default fixture looks like anything another marketplace serves.
  http.get("/api/v1/snapshots/:id/name-collisions", () =>
    HttpResponse.json<Schemas["NameCollisionCheck"]>({
      enabled: true,
      inventoryAvailable: true,
      refused: false,
      collisions: [],
    }),
  ),
  http.get("/api/v1/snapshots/:id/fetchers", () => HttpResponse.json(fetchers)),
  http.get("/api/v1/snapshots/:id/content", () => HttpResponse.json(snapshotContent)),
  http.get("/api/v1/snapshots/:id/provenance", () => HttpResponse.json(compositeProvenance)),
  http.get("/api/v1/snapshots/:id/content-diff", () => HttpResponse.json(contentDiff)),
  http.get("/api/v1/snapshots/:id/files", ({ request }) =>
    HttpResponse.json(filesPage(fileTree, request)),
  ),
  http.get("/api/v1/snapshots/:id/tree", ({ request }) => {
    const listing = treePage(fileTree, snapshotDiff, request);
    return listing
      ? HttpResponse.json(listing)
      : HttpResponse.json({ detail: "no such directory" }, { status: 404 });
  }),
  http.get("/api/v1/snapshots/:id/file", ({ request }) =>
    HttpResponse.json(fileContent(new URL(request.url).searchParams.get("path") ?? "")),
  ),
  http.get("/api/v1/snapshots/:id/diff", ({ request }) =>
    HttpResponse.json(diffPage(snapshotDiff, request)),
  ),
  http.get("/api/v1/tokens", () => HttpResponse.json<Schemas["TokenView"][]>(tokenViews)),
  http.post("/api/v1/tokens", () => HttpResponse.json(issuedToken, { status: 201 })),
  http.get("/api/v1/audit", () => HttpResponse.json({ entries: [], nextBefore: null })),
  http.get("/api/v1/audit/sinks", () => HttpResponse.json<Schemas["SinkView"][]>([auditSink])),
  http.post("/api/v1/audit/sinks", () => HttpResponse.json(createdAuditSink, { status: 201 })),
  http.get("/api/v1/webhooks", () => HttpResponse.json<Schemas["SubscriberView"][]>([subscriber])),
  http.get("/api/v1/webhooks/events", () => HttpResponse.json(eventRegistry)),
  http.get("/api/v1/webhooks/deliveries", () => HttpResponse.json<Schemas["WebhookDelivery"][]>([delivery])),
  http.post("/api/v1/webhooks", () => HttpResponse.json(createdSubscriber, { status: 201 })),
];
