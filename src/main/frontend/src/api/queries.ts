import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import type { components } from "./types.gen";

export type MarketplaceView = components["schemas"]["MarketplaceView"];
export type Snapshot = components["schemas"]["Snapshot"];
export type Provenance = components["schemas"]["Provenance"];
export type TokenView = components["schemas"]["TokenView"];
export type IssuedToken = components["schemas"]["IssuedToken"];
export type SubscriberView = components["schemas"]["SubscriberView"];
export type CreatedSubscriber = components["schemas"]["CreatedSubscriber"];
export type WebhookDelivery = components["schemas"]["WebhookDelivery"];
export type WebhookEventRegistry = components["schemas"]["EventRegistry"];
export type SinkView = components["schemas"]["SinkView"];
export type VettingView = components["schemas"]["VettingView"];
export type VettingRun = components["schemas"]["Run"];
export type VettingVerdict = components["schemas"]["VerdictView"];
export type VettingFinding = components["schemas"]["Finding"];
export type VetterInfo = components["schemas"]["VetterView"];
export type ChainVetter = components["schemas"]["ChainVetterView"];
export type ChainSettings = components["schemas"]["ChainSettingsView"];
export type ChainMode = NonNullable<ChainSettings["mode"]>;
export type ChainModeSetting = components["schemas"]["ChainModeSetting"];
export type ChainOrderSetting = components["schemas"]["ChainOrderSetting"];
export type VetterToggle = components["schemas"]["VetterToggle"];
export type ChainSettingsList = components["schemas"]["ChainSettings"];
export type BulkChainChange = components["schemas"]["BulkChainChange"];
export type BulkChainResult = components["schemas"]["BulkChainResult"];
export type BulkChainOutcome = components["schemas"]["Outcome"];
export type Waiver = components["schemas"]["WaiverView"];
export type WaiverSuppression = components["schemas"]["Suppression"];
export type UncoveredFinding = components["schemas"]["UncoveredFinding"];
export type WaiverScope = NonNullable<Waiver["scope"]>;
export type CreatedSink = components["schemas"]["CreatedSink"];
export type RevetResult = components["schemas"]["RevetResult"];
export type Fetcher = components["schemas"]["Fetcher"];
export type MarketplaceAdoption = components["schemas"]["MarketplaceAdoption"];
export type SnapshotAdoption = components["schemas"]["SnapshotAdoption"];
export type StaleIdentity = components["schemas"]["StaleIdentity"];
export type Eligibility = components["schemas"]["Eligibility"];
export type FourEyesCheck = components["schemas"]["FourEyesCheck"];
export type NameCollisionCheck = components["schemas"]["NameCollisionCheck"];
export type NameCollision = components["schemas"]["NameCollision"];
export type MeView = components["schemas"]["MeView"];
export type EffectiveRole = components["schemas"]["EffectiveRole"];

/** Same-origin download of the NDJSON ledger stream; the session cookie is the credential. */
export const AUDIT_EXPORT_URL = "/api/v1/audit/export";

export function useMe() {
  return useQuery({
    queryKey: ["me"],
    queryFn: () => api<MeView>("/api/v1/me"),
    staleTime: Infinity,
  });
}

export function useMarketplaces() {
  return useQuery({
    queryKey: ["marketplaces"],
    queryFn: () => api<MarketplaceView[]>("/api/v1/marketplaces"),
  });
}

export type RegisteredMarketplace = components["schemas"]["RegisteredMarketplace"];

export function useRegisterMarketplace() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: { name: string; url: string }) =>
      api<RegisteredMarketplace>("/api/v1/marketplaces", {
        method: "POST",
        body: JSON.stringify(request),
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["marketplaces"] }),
  });
}

export function useIngest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (name: string) =>
      api<Snapshot>(`/api/v1/marketplaces/${encodeURIComponent(name)}/ingest`, { method: "POST" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["marketplaces"] }),
  });
}

/**
 * Approving takes no body. A snapshot whose effective vetting outcome is blocked is refused by
 * the server with 409 and the findings no waiver covers; the way past it is to record a waiver
 * per blocking finding, never a flag on this request.
 */
export function useDecideSnapshot() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, decision }: { id: number; decision: "approve" | "reject" }) =>
      api<Snapshot>(`/api/v1/snapshots/${id}/${decision}`, { method: "POST" }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["marketplaces"] });
      void queryClient.invalidateQueries({ queryKey: ["snapshot-vetting"] });
      void queryClient.invalidateQueries({ queryKey: ["snapshot-name-collisions"] });
      void queryClient.invalidateQueries({ queryKey: ["waivers"] });
    },
  });
}

/**
 * Records an accepted risk for one finding. Scope, justification and expiry are all required by
 * the server; the dialog mirrors that but never substitutes for it.
 */
export function useCreateWaiver() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      snapshotId,
      ruleId,
      scope,
      path,
      justification,
      expiresAt,
    }: {
      snapshotId: number;
      ruleId: string;
      scope: WaiverScope;
      path?: string;
      justification: string;
      expiresAt: string;
    }) =>
      api<Waiver>(`/api/v1/snapshots/${snapshotId}/waivers`, {
        method: "POST",
        body: JSON.stringify({ ruleId, scope, path, justification, expiresAt }),
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["snapshot-vetting"] });
      void queryClient.invalidateQueries({ queryKey: ["snapshot-name-collisions"] });
      void queryClient.invalidateQueries({ queryKey: ["waivers"] });
    },
  });
}

/** Withdraws a waiver; the snapshot it was clearing becomes blocked again on the next read. */
export function useRevokeWaiver() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api<Waiver>(`/api/v1/waivers/${id}`, { method: "DELETE" }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["snapshot-vetting"] });
      void queryClient.invalidateQueries({ queryKey: ["snapshot-name-collisions"] });
      void queryClient.invalidateQueries({ queryKey: ["waivers"] });
    },
  });
}

/** A marketplace's waivers, active and lapsed alike. */
export function useWaivers(marketplace: string | null) {
  return useQuery({
    queryKey: ["waivers", marketplace],
    queryFn: () => api<Waiver[]>(`/api/v1/marketplaces/${encodeURIComponent(marketplace!)}/waivers`),
    enabled: marketplace !== null,
  });
}

/**
 * Re-vets an approved snapshot now. The server decides what the answer means: in warn mode — the
 * default — a violation is recorded and nothing is unpublished, and in enforce mode the snapshot
 * is revoked. The button never chooses, which is why there is no mode in the request.
 *
 * @Requirements GW_VETTING_0018
 */
export function useRevetSnapshot() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api<RevetResult>(`/api/v1/snapshots/${id}/revet`, { method: "POST" }),
    onSuccess: () => {
      // The state may have moved to revoked, so the listing is invalidated too, not only the
      // vetting evidence.
      void queryClient.invalidateQueries({ queryKey: ["marketplaces"] });
      void queryClient.invalidateQueries({ queryKey: ["snapshot-vetting"] });
      void queryClient.invalidateQueries({ queryKey: ["snapshot-fetchers"] });
    },
  });
}

/**
 * Who received this snapshot's content through the facade — the blast radius of a retroactive
 * violation. Only fetched for a snapshot that has one, so an ordinary review does not query it.
 */
export function useSnapshotFetchers(snapshotId: number | null) {
  return useQuery({
    queryKey: ["snapshot-fetchers", snapshotId],
    queryFn: () => api<Fetcher[]>(`/api/v1/snapshots/${snapshotId}/fetchers`),
    enabled: snapshotId !== null,
  });
}

/** A snapshot's latest vetting chain run: the evidence a reviewer decides on. */
export function useSnapshotVetting(snapshotId: number | null) {
  return useQuery({
    queryKey: ["snapshot-vetting", snapshotId],
    queryFn: () => api<VettingView>(`/api/v1/snapshots/${snapshotId}/vetting`),
    enabled: snapshotId !== null,
  });
}

/**
 * Whether this session holds the administrative role. A hint only: it decides whether an
 * administrator-only surface is worth rendering, never whether the request behind it is allowed —
 * the server checks every one of them independently.
 */
export function useIsAdmin() {
  const me = useMe();
  return (me.data?.roles ?? []).some((role) => role.role === "admin");
}

/**
 * A marketplace's effective vetting chain: every vetter in the order it runs, the state its
 * enablement resolves to for this marketplace, and which setting decided that.
 *
 * Read from the server rather than recombined here from the settings list: "per-marketplace, else
 * global, else enabled" is the rule that decides what actually runs, and a second copy of it in
 * the browser would be free to disagree with the first.
 *
 * @Requirements GW_VETTING_0029.5
 */
export function useMarketplaceVettingChain(marketplace: string | null) {
  return useQuery({
    queryKey: ["marketplace-vetting-chain", marketplace],
    queryFn: () =>
      api<ChainVetter[]>(`/api/v1/marketplaces/${encodeURIComponent(marketplace ?? "")}/vetting-chain`),
    enabled: marketplace !== null,
  });
}

/**
 * Switch one built-in vetter on or off for one marketplace. Administrator-only at the server,
 * audited there with the vetter, the scope, the new state and the reason.
 *
 * @Requirements GW_VETTING_0029.5
 */
export function useToggleVetter() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: { vetter: string; marketplace?: string; enabled: boolean; reason?: string }) =>
      api<VetterToggle>(`/api/v1/vetting/vetters/${encodeURIComponent(request.vetter)}/toggle`, {
        method: "PUT",
        body: JSON.stringify({
          enabled: request.enabled,
          // An omitted marketplace is the global setting — the server's own contract, not a
          // convention invented here. Sending an empty string would be a different request.
          ...(request.marketplace ? { marketplace: request.marketplace } : {}),
          ...(request.reason ? { reason: request.reason } : {}),
        }),
      }),
    onSuccess: () => invalidateChain(queryClient),
  });
}

/**
 * The chain as it applies to a marketplace with no override of its own: every configured vetter in
 * the order it runs there, and which setting decided each state.
 *
 * Read from the server for the reason {@link useMarketplaceVettingChain} records one level down —
 * the resolution rule lives in the gateway, and a copy of it here would be free to disagree with
 * the one that decides what actually runs.
 *
 * @Requirements GW_VETTING_0035
 */
export function useGlobalVettingChain(enabled = true) {
  return useQuery({
    queryKey: ["global-vetting-chain"],
    queryFn: () => api<ChainVetter[]>("/api/v1/vetting/global-chain"),
    enabled,
  });
}

/**
 * The mode and the order a marketplace with no override runs, with the setting that decided each.
 *
 * @Requirements GW_VETTING_0035
 */
export function useGlobalChainSettings(enabled = true) {
  return useQuery({
    queryKey: ["global-chain-settings"],
    queryFn: () => api<ChainSettings>("/api/v1/vetting/global-chain-settings"),
    enabled,
  });
}

/**
 * Every chain-mode and vetter-order setting there is — the globals and the per-marketplace
 * overrides. The only read that enumerates overrides without asking marketplace by marketplace.
 *
 * @Requirements GW_VETTING_0035
 */
export function useChainSettingsList(enabled = true) {
  return useQuery({
    queryKey: ["chain-settings-list"],
    queryFn: () => api<ChainSettingsList>("/api/v1/vetting/chain-settings"),
    enabled,
  });
}

/**
 * Every vetter enable/disable setting there is, globals and overrides alike.
 *
 * @Requirements GW_VETTING_0035
 */
export function useVetterToggles(enabled = true) {
  return useQuery({
    queryKey: ["vetter-toggles"],
    queryFn: () => api<VetterToggle[]>("/api/v1/vetting/vetter-toggles"),
    enabled,
  });
}

/**
 * One chain change applied to several marketplaces, and the only way to clear an override.
 *
 * The response is read whole, never by status code alone: 207 means at least one marketplace was
 * refused, and `results` is what says which. A caller that reported `applied` as the answer would
 * report a partial failure as a success.
 *
 * @Requirements GW_VETTING_0036
 * @Requirements GW_VETTING_0037
 */
export function useBulkChainSettings() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: BulkChainChange) =>
      api<BulkChainResult>("/api/v1/vetting/chain-settings/bulk", {
        method: "POST",
        body: JSON.stringify(request),
      }),
    onSuccess: () => invalidateChain(queryClient),
  });
}

/**
 * How far the chain runs for one marketplace and the order it runs in, each with the setting that
 * decided it. A sibling of the chain read rather than part of it: these are properties of the
 * chain, not of any one vetter.
 *
 * @Requirements GW_VETTING_0034
 */
export function useMarketplaceChainSettings(marketplace: string | null) {
  return useQuery({
    queryKey: ["marketplace-chain-settings", marketplace],
    queryFn: () =>
      api<ChainSettings>(
        `/api/v1/marketplaces/${encodeURIComponent(marketplace ?? "")}/vetting-chain-settings`,
      ),
    enabled: marketplace !== null,
  });
}

/**
 * Set how far the chain runs for one marketplace. Administrator-only at the server, audited there
 * with the scope, the new mode and the reason.
 *
 * @Requirements GW_VETTING_0034
 */
export function useSetChainMode() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: { mode: ChainMode; marketplace?: string; reason?: string }) =>
      api<ChainModeSetting>("/api/v1/vetting/chain-mode", {
        method: "PUT",
        body: JSON.stringify({
          mode: request.mode,
          ...(request.marketplace ? { marketplace: request.marketplace } : {}),
          ...(request.reason ? { reason: request.reason } : {}),
        }),
      }),
    onSuccess: () => invalidateChain(queryClient),
  });
}

/**
 * Set the order the vetters run in for one marketplace. The whole arrangement is sent at once, so
 * one intended reordering is one audited change rather than one per movement.
 *
 * @Requirements GW_VETTING_0034
 */
export function useSetChainOrder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: { vetters: string[]; marketplace?: string; reason?: string }) =>
      api<ChainOrderSetting>("/api/v1/vetting/chain-order", {
        method: "PUT",
        body: JSON.stringify({
          vetters: request.vetters,
          ...(request.marketplace ? { marketplace: request.marketplace } : {}),
          ...(request.reason ? { reason: request.reason } : {}),
        }),
      }),
    onSuccess: () => invalidateChain(queryClient),
  });
}

/**
 * Every chain read moves together. A mode or an order change reorders one read and re-sources the
 * other, and a global change or a cleared override changes what a marketplace resolves to without
 * touching that marketplace's own row — so a narrower invalidation would leave a stale page.
 */
function invalidateChain(queryClient: ReturnType<typeof useQueryClient>) {
  for (const key of [
    "marketplace-vetting-chain",
    "marketplace-chain-settings",
    "global-vetting-chain",
    "global-chain-settings",
    "chain-settings-list",
    "vetter-toggles",
    // Every chain change writes a ledger row, so a loaded audit page is stale the moment one
    // lands (nothing refetches on focus).
    "audit",
    // And a chain change is exactly what makes stored evidence superseded (GW_VETTING_0038). The
    // marking is derived per request, so the reviewer only sees it once this read is refetched —
    // without this, changing the chain and looking straight at a snapshot shows it as current.
    "snapshot-vetting",
  ]) {
    void queryClient.invalidateQueries({ queryKey: [key] });
  }
}

/**
 * Whether a snapshot has cleared the configured cooling-off window, and how long is left if it
 * has not. The server computes it per request from its own first sighting of the commit, so the
 * portal never has to reason about upstream timestamps — or about its own clock.
 *
 * @Requirements GW_APPROVAL_0004.4
 */
export function useSnapshotReleaseAge(snapshotId: number | null) {
  return useQuery({
    queryKey: ["snapshot-release-age", snapshotId],
    queryFn: () => api<Eligibility>(`/api/v1/snapshots/${snapshotId}/release-age`),
    enabled: snapshotId !== null,
  });
}

/**
 * Whether the separation-of-duties rule objects to *this* reviewer approving *this* snapshot, and
 * what the configured mode would then do about it.
 *
 * The server answers rather than the browser deciding: the waiver clause depends on which waivers
 * the effective-outcome evaluation actually applies, and a second implementation of that here
 * would be a rule that can disagree with the one that decides. The approval endpoint enforces it
 * independently in any case — this only lets the dialog say so before the button is pressed.
 *
 * @Requirements GW_APPROVAL_0010, GW_APPROVAL_0011
 */
export function useSnapshotFourEyes(snapshotId: number | null) {
  return useQuery({
    queryKey: ["snapshot-four-eyes", snapshotId],
    queryFn: () => api<FourEyesCheck>(`/api/v1/snapshots/${snapshotId}/four-eyes`),
    enabled: snapshotId !== null,
  });
}

/**
 * What the name-collision rule would say about approving this snapshot now.
 *
 * @Requirements GW_APPROVAL_0021
 */
export function useSnapshotNameCollisions(snapshotId: number | null) {
  return useQuery({
    queryKey: ["snapshot-name-collisions", snapshotId],
    queryFn: () => api<NameCollisionCheck>(`/api/v1/snapshots/${snapshotId}/name-collisions`),
    enabled: snapshotId !== null,
  });
}

/**
 * The conflicting acts as a reviewer reads them, in the order they happened to the snapshot.
 *
 * @Requirements GW_APPROVAL_0010
 */
export function describeFourEyesConflicts(check: FourEyesCheck | undefined): string {
  const roles = (check?.conflicts ?? []).map((conflict) =>
    conflict.role === "registered-by"
      ? "registered this marketplace"
      : conflict.role === "ingested-by"
        ? "ingested this snapshot"
        : `wrote waiver ${conflict.waiverId} that this approval relies on`,
  );
  return roles.join(", ");
}

/**
 * The remaining wait as a reviewer reads it — `2d 4h`, `45m`. Matches the server's own rendering
 * of the same duration, so the disabled control and the refusal it prevents say the same thing.
 */
export function formatRemaining(seconds: number): string {
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (days > 0) return hours > 0 ? `${days}d ${hours}h` : `${days}d`;
  if (hours > 0) return minutes > 0 ? `${hours}h ${minutes}m` : `${hours}h`;
  if (minutes > 0) return `${minutes}m`;
  return `${Math.max(0, Math.floor(seconds))}s`;
}

/**
 * Retention deletes a snapshot by marking it, so the invalidated view is the marketplace
 * listing the snapshot lives in — nothing is removed from it.
 */
export function useSoftDeleteSnapshot() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api<Snapshot>(`/api/v1/snapshots/${id}`, { method: "DELETE" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["marketplaces"] }),
  });
}

export function useRestoreSnapshot() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api<Snapshot>(`/api/v1/snapshots/${id}/restore`, { method: "POST" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["marketplaces"] }),
  });
}

export function useProvenance(snapshotId: number | null) {
  return useQuery({
    queryKey: ["provenance", snapshotId],
    queryFn: () => api<Provenance>(`/api/v1/snapshots/${snapshotId}/provenance`),
    enabled: snapshotId !== null,
  });
}

export type SnapshotContent = components["schemas"]["SnapshotContent"];

export function useSnapshotContent(snapshotId: number | null) {
  return useQuery({
    queryKey: ["snapshot-content", snapshotId],
    queryFn: () => api<SnapshotContent>(`/api/v1/snapshots/${snapshotId}/content`),
    enabled: snapshotId !== null,
  });
}

export type SnapshotContentDiff = components["schemas"]["ContentDiff"];
export type SnapshotPluginDiff = components["schemas"]["PluginDiff"];
export type SnapshotSkillDiff = components["schemas"]["SkillDiff"];

/**
 * The same inventory, against the marketplace's last approved snapshot: what approving this
 * snapshot would add to what the organisation already accepted. Distinct from `useSnapshotDiff`,
 * which is the file-level delta against what the facade currently serves.
 */
export function useSnapshotContentDiff(snapshotId: number | null) {
  return useQuery({
    queryKey: ["snapshot-content-diff", snapshotId],
    queryFn: () => api<SnapshotContentDiff>(`/api/v1/snapshots/${snapshotId}/content-diff`),
    enabled: snapshotId !== null,
  });
}

export type SnapshotFileTree = components["schemas"]["FileTree"];
export type SnapshotFileContent = components["schemas"]["FileContent"];
export type SnapshotDiff = components["schemas"]["SnapshotDiff"];
export type SnapshotDiffEntry = components["schemas"]["DiffEntryView"];

/** The pinned commit's file tree — the reviewer's map of what the snapshot actually ships. */
export function useSnapshotFiles(snapshotId: number | null) {
  return useQuery({
    queryKey: ["snapshot-files", snapshotId],
    queryFn: () => api<SnapshotFileTree>(`/api/v1/snapshots/${snapshotId}/files`),
    enabled: snapshotId !== null,
  });
}

/** One blob of the pinned commit, as inert text (or metadata only, for a binary blob). */
export function useSnapshotFile(snapshotId: number | null, path: string | null) {
  return useQuery({
    queryKey: ["snapshot-file", snapshotId, path],
    queryFn: () =>
      api<SnapshotFileContent>(
        `/api/v1/snapshots/${snapshotId}/file?path=${encodeURIComponent(path ?? "")}`,
      ),
    enabled: snapshotId !== null && path !== null,
  });
}

/** The delta against the marketplace's currently served commit; null baseline = nothing served. */
export function useSnapshotDiff(snapshotId: number | null) {
  return useQuery({
    queryKey: ["snapshot-diff", snapshotId],
    queryFn: () => api<SnapshotDiff>(`/api/v1/snapshots/${snapshotId}/diff`),
    enabled: snapshotId !== null,
  });
}

/**
 * The adoption report over the fetch ledger: per marketplace, the window's fetches, distinct
 * identities and per-SHA breakdown. The window is part of the key so switching it refetches.
 */
export function useAdoption(days: number) {
  return useQuery({
    queryKey: ["adoption", days],
    queryFn: () => api<MarketplaceAdoption[]>(`/api/v1/adoption?days=${days}`),
  });
}

/** Identities whose most recent fetch is not the served tip — window-free by design. */
export function useStaleness() {
  return useQuery({
    queryKey: ["adoption-staleness"],
    queryFn: () => api<StaleIdentity[]>("/api/v1/adoption/staleness"),
  });
}

/**
 * The newest page of the ledger. The read is paged rather than whole (GW_AUDIT_0008) — the table it
 * reads grows with every client poll — so this is the first page, and `nextBefore` is what a
 * later "load older" control would pass back.
 */
export function useAudit() {
  return useQuery({
    queryKey: ["audit"],
    queryFn: async () => {
      const page = await api<{ entries: Record<string, unknown>[]; nextBefore: number | null }>(
        "/api/v1/audit",
      );
      return page.entries;
    },
  });
}

export function useAuditSinks() {
  return useQuery({
    queryKey: ["audit-sinks"],
    queryFn: () => api<SinkView[]>("/api/v1/audit/sinks"),
  });
}

export function useCreateAuditSink() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: { name: string; url: string }) =>
      api<CreatedSink>("/api/v1/audit/sinks", { method: "POST", body: JSON.stringify(request) }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["audit-sinks"] }),
  });
}

export function useDeleteAuditSink() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api<void>(`/api/v1/audit/sinks/${id}`, { method: "DELETE" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["audit-sinks"] }),
  });
}

/** Replay: rewinding a sink's position re-delivers everything after it. */
export function useResetAuditSinkCursor() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, after }: { id: number; after: number }) =>
      api<SinkView>(`/api/v1/audit/sinks/${id}/cursor`, {
        method: "PUT",
        body: JSON.stringify({ after }),
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["audit-sinks"] }),
  });
}

export function useTokens() {
  return useQuery({
    queryKey: ["tokens"],
    queryFn: () => api<TokenView[]>("/api/v1/tokens"),
  });
}

/**
 * A token creation request as the portal sends it. `expiresAt` is optional because the tokens
 * page does not offer a lifetime; the setup wizard does, and omitting it means a token that
 * never expires — which is what this endpoint has always meant by an absent expiry.
 */
export type CreateToken = { name: string; expiresAt?: string };

export function useCreateToken() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: CreateToken | string) =>
      api<IssuedToken>("/api/v1/tokens", {
        method: "POST",
        body: JSON.stringify(typeof request === "string" ? { name: request } : request),
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["tokens"] }),
  });
}

export function useRevokeToken() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api<void>(`/api/v1/tokens/${id}`, { method: "DELETE" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["tokens"] }),
  });
}

export function useWebhookSubscribers() {
  return useQuery({
    queryKey: ["webhook-subscribers"],
    queryFn: () => api<SubscriberView[]>("/api/v1/webhooks"),
  });
}

/**
 * The server-owned filter vocabulary; the portal never hardcodes event names. The registry
 * also carries an example of each delivery body — published for receiver authors, not used
 * here.
 */
export function useWebhookEvents() {
  return useQuery({
    queryKey: ["webhook-events"],
    queryFn: () => api<WebhookEventRegistry>("/api/v1/webhooks/events"),
    staleTime: Infinity,
  });
}

export function useWebhookDeliveries() {
  return useQuery({
    queryKey: ["webhook-deliveries"],
    queryFn: () => api<WebhookDelivery[]>("/api/v1/webhooks/deliveries?limit=50"),
  });
}

export function useCreateWebhookSubscriber() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: { name: string; url: string; events: string[] }) =>
      api<CreatedSubscriber>("/api/v1/webhooks", { method: "POST", body: JSON.stringify(request) }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["webhook-subscribers"] }),
  });
}

export function useDeleteWebhookSubscriber() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api<void>(`/api/v1/webhooks/${id}`, { method: "DELETE" }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["webhook-subscribers"] });
      void queryClient.invalidateQueries({ queryKey: ["webhook-deliveries"] });
    },
  });
}
