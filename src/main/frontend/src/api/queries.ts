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
export type SinkView = components["schemas"]["SinkView"];
export type VettingView = components["schemas"]["VettingView"];
export type VettingRun = components["schemas"]["Run"];
export type VettingVerdict = components["schemas"]["VerdictView"];
export type VettingFinding = components["schemas"]["Finding"];
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
export type MeView = components["schemas"]["MeView"];
export type EffectiveRole = components["schemas"]["EffectiveRole"];

/** Same-origin download of the NDJSON ledger stream; the session cookie is the credential. */
export const AUDIT_EXPORT_URL = "/api/audit/export";

export function useMe() {
  return useQuery({
    queryKey: ["me"],
    queryFn: () => api<MeView>("/api/me"),
    staleTime: Infinity,
  });
}

export function useMarketplaces() {
  return useQuery({
    queryKey: ["marketplaces"],
    queryFn: () => api<MarketplaceView[]>("/api/marketplaces"),
  });
}

export function useRegisterMarketplace() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: { name: string; url: string }) =>
      api<components["schemas"]["Marketplace"]>("/api/marketplaces", {
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
      api<Snapshot>(`/api/marketplaces/${encodeURIComponent(name)}/ingest`, { method: "POST" }),
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
      api<Snapshot>(`/api/snapshots/${id}/${decision}`, { method: "POST" }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["marketplaces"] });
      void queryClient.invalidateQueries({ queryKey: ["snapshot-vetting"] });
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
      api<Waiver>(`/api/snapshots/${snapshotId}/waivers`, {
        method: "POST",
        body: JSON.stringify({ ruleId, scope, path, justification, expiresAt }),
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["snapshot-vetting"] });
      void queryClient.invalidateQueries({ queryKey: ["waivers"] });
    },
  });
}

/** Withdraws a waiver; the snapshot it was clearing becomes blocked again on the next read. */
export function useRevokeWaiver() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api<Waiver>(`/api/waivers/${id}`, { method: "DELETE" }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["snapshot-vetting"] });
      void queryClient.invalidateQueries({ queryKey: ["waivers"] });
    },
  });
}

/** A marketplace's waivers, active and lapsed alike. */
export function useWaivers(marketplace: string | null) {
  return useQuery({
    queryKey: ["waivers", marketplace],
    queryFn: () => api<Waiver[]>(`/api/marketplaces/${encodeURIComponent(marketplace!)}/waivers`),
    enabled: marketplace !== null,
  });
}

/**
 * Re-vets an approved snapshot now. The server decides what the answer means: in warn mode — the
 * default — a violation is recorded and nothing is unpublished, and in enforce mode the snapshot
 * is revoked. The button never chooses, which is why there is no mode in the request.
 *
 * @Requirements GW_0055
 */
export function useRevetSnapshot() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api<RevetResult>(`/api/snapshots/${id}/revet`, { method: "POST" }),
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
    queryFn: () => api<Fetcher[]>(`/api/snapshots/${snapshotId}/fetchers`),
    enabled: snapshotId !== null,
  });
}

/** A snapshot's latest vetting chain run: the evidence a reviewer decides on. */
export function useSnapshotVetting(snapshotId: number | null) {
  return useQuery({
    queryKey: ["snapshot-vetting", snapshotId],
    queryFn: () => api<VettingView>(`/api/snapshots/${snapshotId}/vetting`),
    enabled: snapshotId !== null,
  });
}

/**
 * Whether a snapshot has cleared the configured cooling-off window, and how long is left if it
 * has not. The server computes it per request from its own first sighting of the commit, so the
 * portal never has to reason about upstream timestamps — or about its own clock.
 *
 * @Requirements GW_0073
 */
export function useSnapshotReleaseAge(snapshotId: number | null) {
  return useQuery({
    queryKey: ["snapshot-release-age", snapshotId],
    queryFn: () => api<Eligibility>(`/api/snapshots/${snapshotId}/release-age`),
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
 * @Requirements GW_0096, GW_0097
 */
export function useSnapshotFourEyes(snapshotId: number | null) {
  return useQuery({
    queryKey: ["snapshot-four-eyes", snapshotId],
    queryFn: () => api<FourEyesCheck>(`/api/snapshots/${snapshotId}/four-eyes`),
    enabled: snapshotId !== null,
  });
}

/**
 * The conflicting acts as a reviewer reads them, in the order they happened to the snapshot.
 *
 * @Requirements GW_0096
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
    mutationFn: (id: number) => api<Snapshot>(`/api/snapshots/${id}`, { method: "DELETE" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["marketplaces"] }),
  });
}

export function useRestoreSnapshot() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api<Snapshot>(`/api/snapshots/${id}/restore`, { method: "POST" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["marketplaces"] }),
  });
}

export function useProvenance(snapshotId: number | null) {
  return useQuery({
    queryKey: ["provenance", snapshotId],
    queryFn: () => api<Provenance>(`/api/snapshots/${snapshotId}/provenance`),
    enabled: snapshotId !== null,
  });
}

export type SnapshotContent = components["schemas"]["SnapshotContent"];

export function useSnapshotContent(snapshotId: number | null) {
  return useQuery({
    queryKey: ["snapshot-content", snapshotId],
    queryFn: () => api<SnapshotContent>(`/api/snapshots/${snapshotId}/content`),
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
    queryFn: () => api<SnapshotContentDiff>(`/api/snapshots/${snapshotId}/content-diff`),
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
    queryFn: () => api<SnapshotFileTree>(`/api/snapshots/${snapshotId}/files`),
    enabled: snapshotId !== null,
  });
}

/** One blob of the pinned commit, as inert text (or metadata only, for a binary blob). */
export function useSnapshotFile(snapshotId: number | null, path: string | null) {
  return useQuery({
    queryKey: ["snapshot-file", snapshotId, path],
    queryFn: () =>
      api<SnapshotFileContent>(
        `/api/snapshots/${snapshotId}/file?path=${encodeURIComponent(path ?? "")}`,
      ),
    enabled: snapshotId !== null && path !== null,
  });
}

/** The delta against the marketplace's currently served commit; null baseline = nothing served. */
export function useSnapshotDiff(snapshotId: number | null) {
  return useQuery({
    queryKey: ["snapshot-diff", snapshotId],
    queryFn: () => api<SnapshotDiff>(`/api/snapshots/${snapshotId}/diff`),
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
    queryFn: () => api<MarketplaceAdoption[]>(`/api/adoption?days=${days}`),
  });
}

/** Identities whose most recent fetch is not the served tip — window-free by design. */
export function useStaleness() {
  return useQuery({
    queryKey: ["adoption-staleness"],
    queryFn: () => api<StaleIdentity[]>("/api/adoption/staleness"),
  });
}

export function useAudit() {
  return useQuery({
    queryKey: ["audit"],
    queryFn: () => api<Record<string, unknown>[]>("/api/audit"),
  });
}

export function useAuditSinks() {
  return useQuery({
    queryKey: ["audit-sinks"],
    queryFn: () => api<SinkView[]>("/api/audit/sinks"),
  });
}

export function useCreateAuditSink() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: { name: string; url: string }) =>
      api<CreatedSink>("/api/audit/sinks", { method: "POST", body: JSON.stringify(request) }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["audit-sinks"] }),
  });
}

export function useDeleteAuditSink() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api<void>(`/api/audit/sinks/${id}`, { method: "DELETE" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["audit-sinks"] }),
  });
}

/** Replay: rewinding a sink's position re-delivers everything after it. */
export function useResetAuditSinkCursor() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, after }: { id: number; after: number }) =>
      api<SinkView>(`/api/audit/sinks/${id}/cursor`, {
        method: "PUT",
        body: JSON.stringify({ after }),
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["audit-sinks"] }),
  });
}

export function useTokens() {
  return useQuery({
    queryKey: ["tokens"],
    queryFn: () => api<TokenView[]>("/api/tokens"),
  });
}

export function useCreateToken() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (name: string) =>
      api<IssuedToken>("/api/tokens", { method: "POST", body: JSON.stringify({ name }) }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["tokens"] }),
  });
}

export function useRevokeToken() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api<void>(`/api/tokens/${id}`, { method: "DELETE" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["tokens"] }),
  });
}

export function useWebhookSubscribers() {
  return useQuery({
    queryKey: ["webhook-subscribers"],
    queryFn: () => api<SubscriberView[]>("/api/webhooks"),
  });
}

/** The server-owned filter vocabulary; the portal never hardcodes event names. */
export function useWebhookEvents() {
  return useQuery({
    queryKey: ["webhook-events"],
    queryFn: () => api<string[]>("/api/webhooks/events"),
    staleTime: Infinity,
  });
}

export function useWebhookDeliveries() {
  return useQuery({
    queryKey: ["webhook-deliveries"],
    queryFn: () => api<WebhookDelivery[]>("/api/webhooks/deliveries?limit=50"),
  });
}

export function useCreateWebhookSubscriber() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: { name: string; url: string; events: string }) =>
      api<CreatedSubscriber>("/api/webhooks", { method: "POST", body: JSON.stringify(request) }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["webhook-subscribers"] }),
  });
}

export function useDeleteWebhookSubscriber() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api<void>(`/api/webhooks/${id}`, { method: "DELETE" }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["webhook-subscribers"] });
      void queryClient.invalidateQueries({ queryKey: ["webhook-deliveries"] });
    },
  });
}
