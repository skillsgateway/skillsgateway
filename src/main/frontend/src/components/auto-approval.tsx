import { useState } from "react";
import { toast } from "sonner";
import {
  useAutoApproval,
  useRevokeAutoApprovalExemption,
  useSetAutoApproval,
  type AutoApprovalRequest,
  type AutoApprovalView,
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

/** The server's limit for automatic approval and for an exemption alike (GW_APPROVAL_0044.1, GW_APPROVAL_0044.3). */
export const MAX_DAYS = 90;

type Choice = "off" | "on";
type AllowedBy = NonNullable<AutoApprovalView["allowedBy"]>;
type Mode = NonNullable<AutoApprovalView["mode"]>;

const ALLOWED_BY: Record<AllowedBy, string> = {
  "internal-source": "Internal source",
  hosted: "Hosted on this gateway",
  exemption: "Administrator exemption",
  "awaiting-second-administrator": "Waiting for a second administrator",
  none: "Not allowed",
};

const MODE: Record<Mode, string> = {
  off: "Off: automatic approval is switched off for this gateway, so no marketplace's automatic approval takes effect",
  "dry-run": "Dry run: snapshots are evaluated and the result recorded, and nothing is published",
  on: "On: an eligible snapshot is published with no person",
};

/** Why automatic approval that is on is not taking effect, when that needs a person to act. */
function note(view: AutoApprovalView): string | undefined {
  if (!view.enabled) return undefined;
  if (lapsed(view.expiresAt)) return "Automatic approval lapsed, so snapshots wait for a person. Renew it to resume.";
  if (view.allowedBy === "awaiting-second-administrator") {
    return "Under the four-eyes rule, the administrator who turned this on has a stake in the marketplace. It takes effect once another administrator turns it on.";
  }
  if (view.allowedBy === "none") {
    return "The marketplace is not an internal source and has no active exemption, so automatic approval does not take effect. Turn it on again with an exemption.";
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

/**
 * The start of the chosen day: always after now, and never more than 90 days from it. A date
 * cannot say "later today", so the earliest choice is tomorrow, where the server would accept any
 * instant after now.
 */
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
function status(view: AutoApprovalView): { label: string; variant: "default" | "secondary" | "outline" } {
  const effective =
    view.allowedBy === "internal-source" || view.allowedBy === "hosted" || view.allowedBy === "exemption";
  if (!view.enabled || !effective) return { label: "Not approved automatically", variant: "outline" };
  if (view.mode === "on") return { label: "Approved automatically", variant: "default" };
  if (view.mode === "dry-run") return { label: "Dry run: recorded, not published", variant: "secondary" };
  return { label: "Not approved automatically", variant: "outline" };
}

/** A refusal as the server states it, with its next step when it gives one. */
function Refusal({ error }: { error: Error }) {
  const nextStep = error instanceof ApiError ? error.nextStep : undefined;
  return (
    <div role="alert" className="space-y-1 text-sm text-destructive break-words">
      <p>{error.message}</p>
      {nextStep ? <p className="text-muted-foreground">Next step: {nextStep}</p> : null}
    </div>
  );
}

/** The revoke confirmation: what ending the exemption does, before it is done. */
export function RevokeExemptionDialog({ marketplace, onClose }: { marketplace: string; onClose: () => void }) {
  const revoke = useRevokeAutoApprovalExemption(marketplace);
  return (
    <Dialog open onOpenChange={(open) => (open || revoke.isPending ? undefined : onClose())}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle className="break-all">Revoke the exemption on {marketplace}</DialogTitle>
          <DialogDescription>
            The exemption ends now. Automatic approval that rests on it stops taking effect, so new snapshots wait for
            a person again. What is already served stays served.
          </DialogDescription>
        </DialogHeader>
        {revoke.isError ? <Refusal error={revoke.error} /> : null}
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
                  toast.success(`Exemption on '${marketplace}' revoked`);
                  onClose();
                },
              })
            }
          >
            {revoke.isPending ? "Revoking…" : "Revoke exemption"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Turning automatic approval on, off, or renewing it. Mirrors the server's rules (a reason that is
 * not blank, an expiry from tomorrow to 90 days out, for automatic approval and the exemption
 * alike); whether the marketplace is an internal source, and the four-eyes rule, are the server's,
 * so their refusals are shown as it states them, with its next step.
 */
export function AutoApprovalForm({ marketplace, view }: { marketplace: string; view: AutoApprovalView }) {
  const set = useSetAutoApproval(marketplace);
  const [choice, setChoice] = useState<Choice>(view.enabled ? "on" : "off");
  const [reason, setReason] = useState(view.reason ?? "");
  const [expires, setExpires] = useState("");
  const [withExemption, setWithExemption] = useState(false);
  const [exemptionReason, setExemptionReason] = useState("");
  const [exemptionExpires, setExemptionExpires] = useState("");
  const min = day(1);
  const max = day(MAX_DAYS);

  const onValid = reason.trim() !== "" && inRange(expires, min, max);
  const exemptionValid = !withExemption || (exemptionReason.trim() !== "" && inRange(exemptionExpires, min, max));
  const valid = choice === "off" || (onValid && exemptionValid);

  const onSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!valid) return;
    const request: AutoApprovalRequest =
      choice === "off"
        ? { enabled: false, reason: reason.trim() || undefined }
        : {
            enabled: true,
            reason: reason.trim(),
            expiresAt: startOf(expires),
            exemption: withExemption
              ? { reason: exemptionReason.trim(), expiresAt: startOf(exemptionExpires) }
              : undefined,
          };
    set.mutate(request, {
      onSuccess: () => toast.success(`Automatic approval of '${marketplace}' saved`),
    });
  };

  return (
    <form onSubmit={onSubmit} className="space-y-4 border-t pt-4" aria-label="Set automatic approval">
      <SegmentedGroup
        label="Automatic approval"
        value={choice}
        options={[
          { value: "off", label: "Off" },
          { value: "on", label: "On" },
        ]}
        onChange={(value) => {
          setChoice(value);
          set.reset();
        }}
      />
      {choice === "on" ? (
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="space-y-2">
            <Label htmlFor="auto-approval-reason">Reason</Label>
            <Input
              id="auto-approval-reason"
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              autoComplete="off"
              placeholder="our own skills repository"
              aria-describedby="auto-approval-hint"
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="auto-approval-expires">Lapses on</Label>
            <Input
              id="auto-approval-expires"
              type="date"
              value={expires}
              min={min}
              max={max}
              onChange={(event) => setExpires(event.target.value)}
              aria-describedby="auto-approval-hint"
            />
          </div>
          <p id="auto-approval-hint" className="text-xs text-muted-foreground sm:col-span-2">
            A reason is required, and a date from tomorrow to {MAX_DAYS} days out. Automatic approval lapses back to
            off at the start of that day; to keep it, renew it here.
          </p>
          <div className="flex items-center gap-2 sm:col-span-2">
            <Checkbox
              id="auto-approval-with-exemption"
              checked={withExemption}
              onCheckedChange={(checked) => setWithExemption(checked === true)}
              aria-describedby="exemption-hint"
            />
            <Label htmlFor="auto-approval-with-exemption">Add an exemption</Label>
          </div>
          <p id="exemption-hint" className="text-xs text-muted-foreground sm:col-span-2">
            Only for a marketplace outside the internal prefixes: the exemption is what automatic approval rests on,
            with its own reason and date, and automatic approval never outlives it.
          </p>
          {withExemption ? (
            <>
              <div className="space-y-2">
                <Label htmlFor="exemption-reason">Exemption reason</Label>
                <Input
                  id="exemption-reason"
                  value={exemptionReason}
                  onChange={(event) => setExemptionReason(event.target.value)}
                  autoComplete="off"
                  placeholder="vendor repository we audit quarterly"
                  aria-describedby="exemption-hint"
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="exemption-expires">Exemption lapses on</Label>
                <Input
                  id="exemption-expires"
                  type="date"
                  value={exemptionExpires}
                  min={min}
                  max={max}
                  onChange={(event) => setExemptionExpires(event.target.value)}
                  aria-describedby="exemption-hint"
                />
              </div>
            </>
          ) : null}
        </div>
      ) : (
        <p className="text-xs text-muted-foreground">
          Every snapshot waits for a person. Saving off also revokes an active exemption.
        </p>
      )}
      {set.isError ? <Refusal error={set.error} /> : null}
      <Button type="submit" disabled={!valid || set.isPending}>
        {set.isPending ? "Saving…" : "Save automatic approval"}
      </Button>
    </form>
  );
}

/**
 * A marketplace's automatic approval on its Settings page (GW_APPROVAL_0044.15, design D15): to
 * anyone who can see the page, whether its snapshots are approved with no person, what allows it,
 * who decided it, why and until when, and the gateway's mode; to an administrator, the form that
 * turns it on, off or renews it and the exemption's revocation. The server enforces every rule.
 *
 * @Requirements GW_APPROVAL_0044.15
 */
export function AutoApproval({ marketplace, canAdminister }: { marketplace: string; canAdminister: boolean }) {
  const autoApproval = useAutoApproval(marketplace);
  const [revoking, setRevoking] = useState(false);
  const view = autoApproval.data;

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-center gap-2">
          <CardTitle>Automatic approval</CardTitle>
          {view ? <Badge variant={status(view).variant}>{status(view).label}</Badge> : null}
        </div>
        <CardDescription>
          Whether this marketplace&apos;s snapshots can be approved with no person, when they need no human judgement.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {autoApproval.isLoading ? <p className="text-sm text-muted-foreground">Loading automatic approval…</p> : null}
        {autoApproval.isError ? (
          <div role="alert" className="flex flex-wrap items-center gap-3 text-sm text-destructive">
            <p>Automatic approval could not be loaded: {autoApproval.error.message}</p>
            <Button
              variant="outline"
              size="sm"
              onClick={() => void autoApproval.refetch()}
              disabled={autoApproval.isFetching}
            >
              Try again
            </Button>
          </div>
        ) : null}
        {view ? (
          <>
            <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-1 text-sm">
              <dt className="font-medium">Status</dt>
              <dd className="flex flex-wrap items-center gap-2">
                {view.enabled ? (
                  <span>
                    On until <Timestamp value={view.expiresAt} />
                  </span>
                ) : (
                  "Off"
                )}
                {view.enabled && lapsed(view.expiresAt) ? <Badge variant="destructive">Lapsed</Badge> : null}
              </dd>
              <dt className="font-medium">Allowed because</dt>
              <dd>{ALLOWED_BY[view.allowedBy ?? "none"]}</dd>
              <dt className="font-medium">Gateway mode</dt>
              <dd>{MODE[view.mode ?? "off"]}</dd>
              <dt className="font-medium">Set by</dt>
              <dd className="break-words">
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
              <dt className="font-medium">Exemption</dt>
              <dd className="break-words">
                {view.exemption ? (
                  <>
                    {view.exemption.reason} · by {view.exemption.createdBy} · lapses{" "}
                    <Timestamp value={view.exemption.expiresAt} />
                  </>
                ) : (
                  "None"
                )}
              </dd>
            </dl>
            {note(view) ? <p className="text-sm text-muted-foreground">{note(view)}</p> : null}
            {canAdminister && view.exemption ? (
              <Button variant="outline" size="sm" onClick={() => setRevoking(true)}>
                Revoke exemption…
              </Button>
            ) : null}
            {canAdminister ? <AutoApprovalForm key={view.setAt ?? "unset"} marketplace={marketplace} view={view} /> : null}
          </>
        ) : null}
      </CardContent>
      {revoking ? <RevokeExemptionDialog marketplace={marketplace} onClose={() => setRevoking(false)} /> : null}
    </Card>
  );
}
