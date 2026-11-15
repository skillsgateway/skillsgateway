import { useState } from "react";
import { toast } from "sonner";
import {
  useApprovalPosture,
  useRevokeApprovalOverride,
  useSetApprovalPosture,
  type PostureRequest,
  type PostureView,
} from "@/api/queries";
import { ApiError } from "@/api/client";
import { SegmentedGroup } from "@/components/segmented-group";
import { Timestamp } from "@/components/timestamp";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

/** The server's limit for a posture and for an override alike (GW_APPROVAL_0044.1, GW_APPROVAL_0044.3). */
export const MAX_DAYS = 90;

type Posture = NonNullable<PostureView["posture"]>;
type Basis = NonNullable<PostureView["basis"]>;
type Mode = NonNullable<PostureView["mode"]>;

const BASIS: Record<Basis, string> = {
  "internal-prefix": "Internal source: under a configured URL prefix",
  hosted: "Internal source: hosted on this gateway",
  override: "Administrator override",
  "awaiting-second-administrator": "Awaiting a second administrator",
  none: "None",
};

const MODE: Record<Mode, string> = {
  off: "Off: automatic approval is switched off for this gateway, so no posture takes effect",
  shadow: "Shadow: snapshots are evaluated and the result recorded, and nothing is published",
  on: "On: an eligible snapshot is published with no person",
};

/** Why an automatic posture is not taking effect, when that needs a person to act. */
function note(view: PostureView): string | undefined {
  if (view.posture !== "auto") return undefined;
  if (lapsed(view.expiresAt)) return "The posture lapsed, so snapshots wait for a person. Renew it to resume.";
  if (view.basis === "awaiting-second-administrator") {
    return "Under the four-eyes rule, the administrator who set this posture has a stake in the marketplace. It takes effect once another administrator sets it.";
  }
  if (view.basis === "none") {
    return "The marketplace is not an internal source and has no active override, so the posture does not take effect. Set it again with an override.";
  }
  return undefined;
}

/** A calendar day in the browser's zone, as a date input holds it. */
function day(offset: number): string {
  const date = new Date();
  date.setDate(date.getDate() + offset);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** The start of the chosen day: always after now, and never more than 90 days from it. */
function startOf(date: string): string {
  return new Date(`${date}T00:00:00`).toISOString();
}

function inRange(date: string, min: string, max: string): boolean {
  return date !== "" && date >= min && date <= max;
}

function lapsed(value: string | undefined): boolean {
  return !!value && new Date(value).getTime() <= Date.now();
}

/** What the card's header says: whether this marketplace's content is published with no person today. */
function status(view: PostureView): { label: string; variant: "default" | "secondary" | "outline" } {
  const effective = view.basis === "internal-prefix" || view.basis === "hosted" || view.basis === "override";
  if (view.posture !== "auto" || !effective) return { label: "Not approved automatically", variant: "outline" };
  if (view.mode === "on") return { label: "Approved automatically", variant: "default" };
  if (view.mode === "shadow") return { label: "Shadow: recorded, not published", variant: "secondary" };
  return { label: "Not approved automatically", variant: "outline" };
}

/** The revoke confirmation: what ending the override does, before it is done. */
export function RevokeOverrideDialog({ marketplace, onClose }: { marketplace: string; onClose: () => void }) {
  const revoke = useRevokeApprovalOverride(marketplace);
  return (
    <Dialog open onOpenChange={(open) => (open || revoke.isPending ? undefined : onClose())}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle className="break-all">Revoke the override on {marketplace}</DialogTitle>
          <DialogDescription>
            The override ends now. An automatic posture that rests on it stops taking effect, so new snapshots wait
            for a person again. What is already served stays served.
          </DialogDescription>
        </DialogHeader>
        {revoke.isError ? (
          <p role="alert" className="text-sm text-destructive break-words">
            {revoke.error.message}
          </p>
        ) : null}
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose} disabled={revoke.isPending}>
            Cancel
          </Button>
          <Button
            type="button"
            variant="destructive"
            disabled={revoke.isPending}
            onClick={() =>
              revoke.mutate(undefined, {
                onSuccess: () => {
                  toast.success(`Override on '${marketplace}' revoked`);
                  onClose();
                },
              })
            }
          >
            {revoke.isPending ? "Revoking…" : "Revoke override"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Setting or renewing the posture. Mirrors the server's rules (a reason that is not blank, an
 * expiry from tomorrow to 90 days out, for the posture and the override alike); whether the
 * marketplace has an internal basis, and the four-eyes rule, are the server's, so their refusals
 * are shown as it states them, with its next step.
 */
export function ApprovalPostureForm({ marketplace, view }: { marketplace: string; view: PostureView }) {
  const set = useSetApprovalPosture(marketplace);
  const [posture, setPosture] = useState<Posture>(view.posture ?? "manual");
  const [reason, setReason] = useState(view.reason ?? "");
  const [expires, setExpires] = useState("");
  const [withOverride, setWithOverride] = useState(false);
  const [overrideReason, setOverrideReason] = useState("");
  const [overrideExpires, setOverrideExpires] = useState("");
  const min = day(1);
  const max = day(MAX_DAYS);

  const autoValid = reason.trim() !== "" && inRange(expires, min, max);
  const overrideValid = !withOverride || (overrideReason.trim() !== "" && inRange(overrideExpires, min, max));
  const valid = posture === "manual" || (autoValid && overrideValid);

  const onSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!valid) return;
    const request: PostureRequest =
      posture === "manual"
        ? { posture, reason: reason.trim() || undefined }
        : {
            posture,
            reason: reason.trim(),
            expiresAt: startOf(expires),
            override: withOverride
              ? { reason: overrideReason.trim(), expiresAt: startOf(overrideExpires) }
              : undefined,
          };
    set.mutate(request, {
      onSuccess: () => toast.success(`Approval posture of '${marketplace}' saved`),
    });
  };
  const refused = set.error instanceof ApiError ? set.error : undefined;

  return (
    <form onSubmit={onSubmit} className="space-y-4 border-t pt-4" aria-label="Set the approval posture">
      <SegmentedGroup
        label="Posture"
        value={posture}
        options={[
          { value: "manual", label: "Manual" },
          { value: "auto", label: "Automatic" },
        ]}
        onChange={(value) => {
          setPosture(value);
          set.reset();
        }}
      />
      {posture === "auto" ? (
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="space-y-2">
            <Label htmlFor="posture-reason">Reason</Label>
            <Input
              id="posture-reason"
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              autoComplete="off"
              placeholder="our own skills repository"
              aria-describedby="posture-hint"
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="posture-expires">Lapses on</Label>
            <Input
              id="posture-expires"
              type="date"
              value={expires}
              min={min}
              max={max}
              onChange={(event) => setExpires(event.target.value)}
              aria-describedby="posture-hint"
            />
          </div>
          <p id="posture-hint" className="text-xs text-muted-foreground sm:col-span-2">
            A reason is required, and a date from tomorrow to {MAX_DAYS} days out. The posture lapses back to manual
            at the start of that day; to keep it, renew it here.
          </p>
          <div className="flex items-center gap-2 sm:col-span-2">
            <Checkbox
              id="posture-with-override"
              checked={withOverride}
              onCheckedChange={(checked) => setWithOverride(checked === true)}
              aria-describedby="override-hint"
            />
            <Label htmlFor="posture-with-override">Add an override</Label>
          </div>
          <p id="override-hint" className="text-xs text-muted-foreground sm:col-span-2">
            Only for a marketplace outside the internal prefixes: the override is what the posture rests on, with its
            own reason and date, and the posture never outlives it.
          </p>
          {withOverride ? (
            <>
              <div className="space-y-2">
                <Label htmlFor="override-reason">Override reason</Label>
                <Input
                  id="override-reason"
                  value={overrideReason}
                  onChange={(event) => setOverrideReason(event.target.value)}
                  autoComplete="off"
                  placeholder="vendor repository we audit quarterly"
                  aria-describedby="override-hint"
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="override-expires">Override lapses on</Label>
                <Input
                  id="override-expires"
                  type="date"
                  value={overrideExpires}
                  min={min}
                  max={max}
                  onChange={(event) => setOverrideExpires(event.target.value)}
                  aria-describedby="override-hint"
                />
              </div>
            </>
          ) : null}
        </div>
      ) : (
        <p className="text-xs text-muted-foreground">
          Every snapshot waits for a person. Saving manual also revokes an active override.
        </p>
      )}
      {refused ? (
        <div role="alert" className="space-y-1 text-sm text-destructive break-words">
          <p>{refused.message}</p>
          {refused.nextStep ? <p className="text-muted-foreground">Next step: {refused.nextStep}</p> : null}
        </div>
      ) : null}
      <Button type="submit" disabled={!valid || set.isPending}>
        {set.isPending ? "Saving…" : "Save posture"}
      </Button>
    </form>
  );
}

/**
 * A marketplace's approval posture on its Settings page (GW_APPROVAL_0044.15, design D15): to
 * anyone who can see the page, whether its snapshots are approved with no person, on what basis,
 * who decided it, why and until when, and the deployment's mode; to an administrator, the form that
 * sets or renews it and the override's revocation. The server enforces every rule.
 *
 * @Requirements GW_APPROVAL_0044.15
 */
export function ApprovalPosture({ marketplace, canAdminister }: { marketplace: string; canAdminister: boolean }) {
  const posture = useApprovalPosture(marketplace);
  const [revoking, setRevoking] = useState(false);
  const view = posture.data;

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-center gap-2">
          <CardTitle>Approval posture</CardTitle>
          {view ? <Badge variant={status(view).variant}>{status(view).label}</Badge> : null}
        </div>
        <CardDescription>
          Whether this marketplace&apos;s snapshots can be approved with no person, when they need no human judgement.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {posture.isLoading ? <p className="text-sm text-muted-foreground">Loading the approval posture…</p> : null}
        {posture.isError ? (
          <p role="alert" className="text-sm text-destructive">
            The approval posture could not be loaded: {posture.error.message}
          </p>
        ) : null}
        {view ? (
          <>
            <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-1 text-sm">
              <dt className="font-medium">Posture</dt>
              <dd className="flex flex-wrap items-center gap-2">
                {view.posture === "auto" ? "Automatic" : "Manual"}
                {view.posture === "auto" && lapsed(view.expiresAt) ? (
                  <Badge variant="destructive">Lapsed</Badge>
                ) : null}
              </dd>
              <dt className="font-medium">Basis</dt>
              <dd>{BASIS[view.basis ?? "none"]}</dd>
              <dt className="font-medium">Deployment mode</dt>
              <dd>{MODE[view.mode ?? "off"]}</dd>
              <dt className="font-medium">Set by</dt>
              <dd>
                {view.setBy ?? "—"}
                {view.setAt ? (
                  <>
                    {" · "}
                    <Timestamp value={view.setAt} />
                  </>
                ) : null}
              </dd>
              <dt className="font-medium">Reason</dt>
              <dd className="break-words">{view.reason ?? "—"}</dd>
              <dt className="font-medium">{view.posture === "auto" && lapsed(view.expiresAt) ? "Lapsed" : "Lapses"}</dt>
              <dd>
                <Timestamp value={view.posture === "auto" ? view.expiresAt : undefined} />
              </dd>
              <dt className="font-medium">Override</dt>
              <dd className="break-words">
                {view.override ? (
                  <>
                    {view.override.reason} · by {view.override.createdBy} · lapses{" "}
                    <Timestamp value={view.override.expiresAt} />
                  </>
                ) : (
                  "None"
                )}
              </dd>
            </dl>
            {note(view) ? <p className="text-sm text-muted-foreground">{note(view)}</p> : null}
            {canAdminister && view.override ? (
              <Button variant="outline" size="sm" onClick={() => setRevoking(true)}>
                Revoke override…
              </Button>
            ) : null}
            {canAdminister ? <ApprovalPostureForm key={view.setAt ?? "unset"} marketplace={marketplace} view={view} /> : null}
          </>
        ) : null}
      </CardContent>
      {revoking ? <RevokeOverrideDialog marketplace={marketplace} onClose={() => setRevoking(false)} /> : null}
    </Card>
  );
}
