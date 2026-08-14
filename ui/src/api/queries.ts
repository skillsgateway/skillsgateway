import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import type { components } from "./types.gen";

export type MarketplaceView = components["schemas"]["MarketplaceView"];
export type Snapshot = components["schemas"]["Snapshot"];
export type Provenance = components["schemas"]["Provenance"];
export type TokenView = components["schemas"]["TokenView"];
export type IssuedToken = components["schemas"]["IssuedToken"];

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

export function useDecideSnapshot() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, decision }: { id: number; decision: "approve" | "reject" }) =>
      api<Snapshot>(`/api/snapshots/${id}/${decision}`, { method: "POST" }),
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
