import { useEffect, useState } from "react";
import { ArrowDown, ArrowUp } from "lucide-react";
import { toast } from "sonner";
import {
  useMarketplaceChainSettings,
  useMarketplaceVettingChain,
  useSetChainMode,
  useSetChainOrder,
  useToggleVetter,
  type ChainMode,
  type ChainSettings,
} from "@/api/queries";
import { SegmentedGroup } from "@/components/segmented-group";
import { VettingFlow } from "@/components/vetting-flow";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { marketplaceFlow, marketplaceHeadline, sourceWord, type FlowNode } from "@/lib/vetting-flow";

/**
 * The switch itself, inside the node it belongs to. A reason is optional — the server accepts a
 * toggle without one — so the control is never disabled for want of it; the hint says what the
 * note is for rather than pretending it is required.
 */
function ToggleControls({ node, marketplace }: { node: FlowNode; marketplace: string }) {
  const toggle = useToggleVetter();
  const [reason, setReason] = useState("");
  const vetter = node.setting?.name ?? "";
  const enabled = node.setting?.enabled === true;
  const next = !enabled;
  const hintId = `vetter-toggle-hint-${vetter}`;

  return (
    <div className="mt-2 space-y-2 rounded-md border bg-muted/40 p-3">
      <div className="space-y-1">
        <Label htmlFor={`vetter-reason-${vetter}`}>Reason (optional)</Label>
        <Input
          id={`vetter-reason-${vetter}`}
          autoComplete="off"
          value={reason}
          aria-describedby={hintId}
          onChange={(event) => setReason(event.target.value)}
          placeholder={next ? "Why should this vetter run here?" : "Why is this vetter off here?"}
        />
      </div>
      <p id={hintId} className="text-xs text-muted-foreground">
        The change is scoped to this marketplace and overrides the global setting. It is recorded on
        the audit ledger with your identity, the vetter, the scope, the new state and this note.
      </p>
      <Button
        size="sm"
        variant={next ? "default" : "outline"}
        disabled={toggle.isPending}
        aria-label={`${next ? "Enable" : "Disable"} ${vetter} for ${marketplace}`}
        onClick={() =>
          toggle.mutate(
            { vetter, marketplace, enabled: next, reason: reason.trim() || undefined },
            {
              onSuccess: () => {
                toast.success(`${vetter} ${next ? "enabled" : "disabled"} for ${marketplace}`);
                setReason("");
              },
              onError: (error) => toast.error(error.message),
            },
          )
        }
      >
        {toggle.isPending
          ? next
            ? "Enabling…"
            : "Disabling…"
          : next
            ? "Enable for this marketplace"
            : "Disable for this marketplace"}
      </Button>
    </div>
  );
}

const MODES: readonly { value: ChainMode; label: string }[] = [
  { value: "run-all", label: "Run every vetter" },
  { value: "stop-after-fail", label: "Stop after a failure" },
];

/**
 * How far the chain runs. The cost of stopping early is stated beside the control rather than
 * discovered later on a snapshot: the saving is real, and so is the loss of a complete verdict set.
 *
 * @Requirements GW_VETTING_0034
 */
export function ModeControls({ settings, marketplace }: { settings: ChainSettings; marketplace: string }) {
  const setMode = useSetChainMode();
  const [reason, setReason] = useState("");
  const mode = settings.mode ?? "run-all";
  const hintId = "chain-mode-hint";

  return (
    <div className="space-y-2 rounded-md border bg-muted/40 p-3">
      <SegmentedGroup
        label="When a vetter fails"
        value={mode}
        options={MODES}
        describedBy={hintId}
        onChange={(next) => {
          if (next === mode) return;
          setMode.mutate(
            { mode: next, marketplace, reason: reason.trim() || undefined },
            {
              onSuccess: () => {
                toast.success(
                  next === "stop-after-fail"
                    ? `The chain will stop at the first failure for ${marketplace}`
                    : `Every vetter will run for ${marketplace}`,
                );
                setReason("");
              },
              onError: (error) => toast.error(error.message),
            },
          );
        }}
      />
      <p id={hintId} className="text-xs text-muted-foreground">
        Stopping early spares the vetters after a failure — the ones billed per call, or that run a
        sandbox — but it also means a reviewer no longer sees everything that is wrong with a
        snapshot in one pass, and a run that stopped is blocked until it has been run again, even
        after the finding that stopped it is waived. Currently {sourceWord(settings.modeSource)}.
      </p>
      <div className="space-y-1">
        <Label htmlFor="chain-mode-reason">Reason (optional)</Label>
        <Input
          id="chain-mode-reason"
          autoComplete="off"
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          placeholder="Why should this marketplace stop early?"
        />
      </div>
    </div>
  );
}

/**
 * The order the vetters run in, reordered by named buttons and saved as one arrangement.
 *
 * Buttons rather than a drag gesture: the movement has to be operable without a pointer, and a
 * keyboard path bolted beside a drag implementation is a second implementation to keep correct.
 * The arrangement is saved explicitly, so one intended reordering is one audited change on the
 * ledger rather than one per hop.
 *
 * @Requirements GW_VETTING_0034
 */
export function OrderControls({
  settings,
  marketplace,
  pending,
  onPending,
}: {
  settings: ChainSettings;
  marketplace: string;
  pending: string[] | null;
  onPending: (order: string[] | null) => void;
}) {
  const setOrder = useSetChainOrder();
  const saved = settings.order ?? [];
  const order = pending ?? saved;
  const dirty = pending !== null;
  const [moved, setMoved] = useState<{ vetter: string; position: number } | null>(null);

  const buttonId = (direction: "up" | "down", vetter: string) =>
    `chain-order-${direction}-${cssId(vetter)}`;

  const move = (index: number, by: number) => {
    const next = [...order];
    const target = index + by;
    if (target < 0 || target >= next.length) return;
    const vetter = next[index]!;
    [next[index], next[target]] = [next[target]!, vetter];
    onPending(next);
    setMoved({ vetter, position: target + 1 });
  };

  // A movement that lands a vetter at an end disables the button that was just pressed, and a
  // disabled control cannot hold focus — so a keyboard user would be dropped to the top of the
  // document mid-task. Focus follows the vetter instead: the same control at its new position, or
  // the opposite one when that end is now reached.
  useEffect(() => {
    if (moved === null) return;
    const index = order.indexOf(moved.vetter);
    if (index < 0) return;
    const candidates = [
      document.getElementById(buttonId("up", moved.vetter)),
      document.getElementById(buttonId("down", moved.vetter)),
    ].filter((element): element is HTMLElement => element !== null && !isDisabled(element));
    const preferred = document.activeElement;
    if (candidates.length > 0 && !candidates.includes(preferred as HTMLElement)) {
      candidates[0]!.focus();
    }
  }, [moved, order]);

  return (
    <div className="space-y-2 rounded-md border bg-muted/40 p-3">
      <p className="text-sm leading-none font-medium">The order they run in</p>
      {/*
        The movement is a change to a list a sighted user watches renumber, and a screen-reader
        user does not. Announcing the vetter and its new position is the equivalent feedback; the
        region is always rendered so the first announcement is not lost to a late insertion.
      */}
      <p aria-live="polite" className="sr-only">
        {moved === null
          ? ""
          : `${moved.vetter} moved to position ${moved.position} of ${order.length}`}
      </p>
      <p id="chain-order-hint" className="text-xs text-muted-foreground">
        Order changes nothing unless the chain stops early — then it decides which vetters get to
        look at all, so the cheap and deterministic ones belong first. Currently{" "}
        {sourceWord(settings.orderSource)}.
      </p>
      <ol aria-describedby="chain-order-hint" aria-label={`Vetter order of ${marketplace}`}>
        {order.map((vetter, index) => (
          <li key={vetter} className="flex items-center gap-2 border-b py-1.5 last:border-b-0">
            <span className="w-6 text-xs text-muted-foreground tabular-nums">{index + 1}</span>
            <span className="min-w-0 flex-1 truncate text-sm">{vetter}</span>
            <Button
              type="button"
              id={buttonId("up", vetter)}
              size="icon"
              variant="outline"
              disabled={index === 0}
              aria-label={`Move ${vetter} up`}
              onClick={() => move(index, -1)}
            >
              <ArrowUp className="size-4" aria-hidden />
            </Button>
            <Button
              type="button"
              id={buttonId("down", vetter)}
              size="icon"
              variant="outline"
              disabled={index === order.length - 1}
              aria-label={`Move ${vetter} down`}
              onClick={() => move(index, 1)}
            >
              <ArrowDown className="size-4" aria-hidden />
            </Button>
          </li>
        ))}
      </ol>
      <div className="flex flex-wrap gap-2">
        <Button
          size="sm"
          disabled={!dirty || setOrder.isPending}
          onClick={() =>
            setOrder.mutate(
              { vetters: order, marketplace },
              {
                onSuccess: () => {
                  toast.success(`Vetter order saved for ${marketplace}`);
                  onPending(null);
                },
                onError: (error) => toast.error(error.message),
              },
            )
          }
        >
          {setOrder.isPending ? "Saving…" : "Save order"}
        </Button>
        <Button size="sm" variant="outline" disabled={!dirty} onClick={() => onPending(null)}>
          Discard changes
        </Button>
      </div>
      {dirty ? (
        <p role="status" className="text-xs text-muted-foreground">
          Not saved yet. The chain above shows the arrangement you are proposing.
        </p>
      ) : null}
    </div>
  );
}

/**
 * The effective vetting chain of one marketplace, drawn the way a snapshot's chain is drawn but
 * without verdicts: what will run, in what order, how far, whether each vetter is on, and which
 * setting decided each of those.
 *
 * Administrator-only. The caller decides whether to mount it; the server refuses the read to
 * anyone else regardless (GW_VETTING_0029.4), which is what actually protects it.
 *
 * @Requirements GW_VETTING_0029.5
 * @Requirements GW_VETTING_0034
 */
export function MarketplaceVettingChain({ marketplace }: { marketplace: string }) {
  const chain = useMarketplaceVettingChain(marketplace);
  const settings = useMarketplaceChainSettings(marketplace);
  const [pendingOrder, setPendingOrder] = useState<string[] | null>(null);

  // A saved order, or another administrator's, replaces what this page was proposing: the drawing
  // must never keep showing an arrangement that is no longer on offer.
  useEffect(() => setPendingOrder(null), [marketplace, settings.data?.order]);

  const nodes = chain.data ? marketplaceFlow(chain.data) : [];
  const flow = pendingOrder === null ? nodes : reorder(nodes, pendingOrder);

  return (
    <Card>
      <CardHeader>
        <CardTitle>Vetting chain</CardTitle>
        <CardDescription>
          What runs against every snapshot of this marketplace, in order, how far the chain goes, and
          where each setting comes from. Narrowing or shortening the chain narrows the evidence
          behind an approval; it never makes one automatic.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {chain.isLoading || settings.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading the chain…</p>
        ) : null}
        {chain.isError ? (
          <p role="alert" className="text-sm text-destructive">
            {chain.error.message}
          </p>
        ) : null}
        {settings.isError ? (
          <p role="alert" className="text-sm text-destructive">
            {settings.error.message}
          </p>
        ) : null}
        {chain.data ? (
          <VettingFlow
            label={`Vetting chain of ${marketplace}`}
            headline={marketplaceHeadline(flow, settings.data?.mode)}
            nodes={flow}
            detailFooter={(node) =>
              node.kind === "setting" ? <ToggleControls node={node} marketplace={marketplace} /> : null
            }
          />
        ) : null}
        {settings.data ? (
          <div className="grid gap-3 md:grid-cols-2">
            <ModeControls settings={settings.data} marketplace={marketplace} />
            <OrderControls
              settings={settings.data}
              marketplace={marketplace}
              pending={pendingOrder}
              onPending={setPendingOrder}
            />
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}

/** A vetter name as a fragment of an element id; vetter names are already url-safe, but not by rule. */
function cssId(vetter: string): string {
  return vetter.replace(/[^a-zA-Z0-9_-]/g, "_");
}

/** Whether a control is one focus cannot rest on. */
function isDisabled(element: HTMLElement): boolean {
  return element.hasAttribute("disabled") || element.getAttribute("aria-disabled") === "true";
}

/** The drawn chain in a proposed arrangement, with the step numbers it would have. */
function reorder(nodes: FlowNode[], order: string[]): FlowNode[] {
  const settings = nodes.filter((node) => node.kind === "setting");
  const byName = new Map(settings.map((node) => [node.setting?.name ?? "", node]));
  const arranged = order
    .map((name) => byName.get(name))
    .filter((node): node is FlowNode => node !== undefined)
    .map((node, index) => ({ ...node, eyebrow: `Step ${index + 1}` }));
  const before = nodes.slice(0, nodes.indexOf(settings[0] ?? nodes[0]!));
  const after = nodes.slice(nodes.indexOf(settings.at(-1) ?? nodes[0]!) + 1);
  return [...before, ...arranged, ...after];
}
