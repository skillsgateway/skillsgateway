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
export type CreatedSink = components["schemas"]["CreatedSink"];

/** Same-origin download of the NDJSON ledger stream; the session cookie is the credential. */
export const AUDIT_EXPORT_URL = "/api/audit/export";

export function useMe() {
  return useQuery({
    queryKey: ["me"],
    queryFn: () => api<{ username: string }>("/api/me"),
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
 * Approving a snapshot whose vetting chain blocked requires a reason; the server is
 * authoritative and answers 409 without one, so the reason travels as an ordinary field
 * rather than as a client-side precondition.
 */
export function useDecideSnapshot() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      decision,
      overrideReason,
    }: {
      id: number;
      decision: "approve" | "reject";
      overrideReason?: string;
    }) =>
      api<Snapshot>(`/api/snapshots/${id}/${decision}`, {
        method: "POST",
        ...(overrideReason ? { body: JSON.stringify({ overrideReason }) } : {}),
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["marketplaces"] });
      void queryClient.invalidateQueries({ queryKey: ["snapshot-vetting"] });
    },
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
