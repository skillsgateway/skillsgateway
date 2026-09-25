import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { ArrowDown, ArrowUp, ShieldAlert } from "lucide-react";
import { toast } from "sonner";
import {
  useBulkChainSettings,
  useChainSettingsList,
  useGlobalChainSettings,
  useGlobalVettingChain,
  useIsAdmin,
  useMarketplaces,
  useMe,
  useVetterToggles,
  type BulkChainChange,
  type BulkChainResult,
  type ChainMode,
} from "@/api/queries";
import { SegmentedGroup } from "@/components/segmented-group";
import { VettingFlow } from "@/components/vetting-flow";
import {
  ModeControls,
  OrderControls,
  ToggleControls,
  reorder,
} from "@/components/vetting-chain-controls";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { marketplaceFlow, marketplaceHeadline } from "@/lib/vetting-flow";
import {
  actionSentence,
  overrideSummary,
  overridesOf,
  plannedChanges,
  type BulkAction,
  type MarketplaceOverride,
} from "@/lib/vetting-overrides";

/**
 * Governing the vetting chain across the estate: the chain a marketplace with no override of its
 * own runs, every marketplace that departs from it, and one change applied to many at once.
 *
 * Administrator-only. The sidebar does not offer it to anyone else and this page states its refusal
 * rather than rendering an empty shell — but the server refuses all five reads independently, which
 * is what actually protects them.
 *
 * @Requirements GW_VETTING_0035
 * @Requirements GW_VETTING_0036
 * @Requirements GW_VETTING_0037
 */
export function VettingPage() {
  const me = useMe();
  const isAdmin = useIsAdmin();

  if (me.isLoading) {
    return <p className="text-sm text-muted-foreground">Loading…</p>;
  }
  if (!isAdmin) {
    return <VettingRefusal />;
  }
  return <Governance />;
}

/**
 * What a session without the administrative role sees if it reaches the address anyway. It says
 * what is behind the page and what is missing, because a refusal that explains nothing is one the
 * reader can only interpret as a fault.
 */
export function VettingRefusal() {
  return (
    <div className="space-y-4">
      <PageHeading />
      <Card>
        <CardContent className="flex items-start gap-3 py-6">
          <ShieldAlert className="mt-0.5 size-5 shrink-0 text-muted-foreground" aria-hidden />
          <div className="space-y-1">
            <p role="alert" className="text-sm font-medium">
              This page needs the administrative role.
            </p>
            <p className="text-sm text-muted-foreground">
              The settings here decide how much evidence stands behind every approval in every
              marketplace, so neither they nor their current values are shown to marketplace-scoped
              approvers or to auditors. Ask an administrator for the role, or read the chain of a
              marketplace you can already reach from{" "}
              <Link
                to="/marketplaces"
                className="font-medium text-primary underline-offset-4 hover:underline"
              >
                Marketplaces
              </Link>
              .
            </p>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

function PageHeading() {
  return (
    <div className="space-y-1">
      <h1 className="text-2xl font-semibold">Vetting chain</h1>
      <p className="text-sm text-muted-foreground">
        The chain every marketplace runs unless it says otherwise, and the ones that say otherwise.
      </p>
    </div>
  );
}

function Governance() {
  const chain = useGlobalVettingChain();
  const settings = useGlobalChainSettings();
  const settingsList = useChainSettingsList();
  const toggles = useVetterToggles();
  const marketplaces = useMarketplaces();
  const [pendingOrder, setPendingOrder] = useState<string[] | null>(null);

  // A saved order, or another administrator's, replaces what this page was proposing.
  useEffect(() => setPendingOrder(null), [settings.data?.order]);

  const overrides = useMemo(
    () => overridesOf(settingsList.data, toggles.data, marketplaces.data),
    [settingsList.data, toggles.data, marketplaces.data],
  );
  const vetterNames = useMemo(
    () => (chain.data ?? []).map((vetter) => vetter.name ?? ""),
    [chain.data],
  );

  const nodes = chain.data ? marketplaceFlow(chain.data) : [];
  const flow = pendingOrder === null ? nodes : reorder(nodes, pendingOrder);

  const failed = [chain, settings, settingsList, toggles, marketplaces].filter(
    (query) => query.isError,
  );

  return (
    <div className="space-y-6">
      <PageHeading />

      {failed.map((query) => (
        <p key={query.error?.message} role="alert" className="text-sm text-destructive">
          {query.error?.message}
        </p>
      ))}

      <Card>
        <CardHeader>
          <CardTitle>The default chain</CardTitle>
          <CardDescription>
            What runs against a snapshot of any marketplace that overrides nothing of its own: which
            vetters, in what order, and how far the chain goes. A marketplace with an override of its
            own keeps it — clear the override below to bring it back here.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          {chain.isLoading || settings.isLoading ? (
            <p className="text-sm text-muted-foreground">Loading the chain…</p>
          ) : null}
          {chain.data ? (
            <VettingFlow
              label="The default vetting chain"
              headline={marketplaceHeadline(flow, settings.data?.mode, "default")}
              nodes={flow}
              detailFooter={(node) => (node.kind === "setting" ? <ToggleControls node={node} /> : null)}
            />
          ) : null}
          {settings.data ? (
            <div className="grid gap-3 md:grid-cols-2">
              <ModeControls settings={settings.data} />
              <OrderControls
                settings={settings.data}
                pending={pendingOrder}
                onPending={setPendingOrder}
              />
            </div>
          ) : null}
        </CardContent>
      </Card>

      <OverridesCard
        overrides={overrides}
        loading={settingsList.isLoading || toggles.isLoading || marketplaces.isLoading}
      />

      <BulkCard
        overrides={overrides}
        marketplaces={(marketplaces.data ?? [])
          .map((marketplace) => marketplace.name ?? "")
          .filter((name) => name !== "")}
        vetters={vetterNames}
      />
    </div>
  );
}

/** The overrides section wired to the gateway; the table below is what it draws. */
function OverridesCard({
  overrides,
  loading,
}: {
  overrides: MarketplaceOverride[];
  loading: boolean;
}) {
  const bulk = useBulkChainSettings();
  const [clearing, setClearing] = useState<string | null>(null);

  const clear = (row: MarketplaceOverride) => {
    if (!row.name) return;
    setClearing(row.name);
    bulk.mutate(
      {
        marketplaces: [row.name],
        action: "clear",
        clear: ["mode", "order", "vetters"],
        reason: "back to the default chain",
      },
      {
        onSettled: () => setClearing(null),
        onSuccess: (result) => {
          // Even one marketplace can be refused, and a refusal that toasts success is the lie this
          // page exists not to tell.
          if ((result.failed ?? 0) > 0) {
            toast.error(result.results?.[0]?.detail ?? `Could not clear ${row.name}`);
          } else {
            toast.success(`${row.name} is back on the default chain`);
          }
        },
        onError: (error) => toast.error(error.message),
      },
    );
  };

  return <OverridesTable overrides={overrides} loading={loading} clearing={clearing} onClear={clear} />;
}

/**
 * One row per marketplace that departs from the default, and what it departs in.
 *
 * Presentational, so the states that matter — nothing overridden, several overridden, a setting
 * whose marketplace is gone — are stories rather than fixtures nobody can reach.
 *
 * @Requirements GW_VETTING_0035
 * @Requirements GW_VETTING_0036
 */
export function OverridesTable({
  overrides,
  loading = false,
  clearing = null,
  onClear,
}: {
  overrides: MarketplaceOverride[];
  loading?: boolean;
  clearing?: string | null;
  onClear: (row: MarketplaceOverride) => void;
}) {
  const clear = onClear;
  return (
    <Card>
      <CardHeader>
        <CardTitle>Overrides</CardTitle>
        <CardDescription>
          Marketplaces whose chain departs from the default, and in what. Clearing an override is not
          the same as setting it back to the default value: an override that agrees with the default
          still pins the marketplace, so the next change above would pass it by.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {loading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : overrides.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No marketplace overrides the default chain. Everything in the estate runs exactly what is
            above.
          </p>
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Marketplace</TableHead>
                  <TableHead>Overrides</TableHead>
                  <TableHead className="text-right">Action</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {overrides.map((row) => (
                  <TableRow key={row.label}>
                    <TableCell className="font-medium">
                      {row.name ? (
                        <Link
                          to={`/marketplaces/${encodeURIComponent(row.name)}`}
                          className="text-primary underline-offset-4 hover:underline"
                        >
                          {row.name}
                        </Link>
                      ) : (
                        <span className="text-muted-foreground">
                          {row.label} — no longer registered
                        </span>
                      )}
                    </TableCell>
                    <TableCell>
                      {/* Stat chips, not badges: these are values a marketplace was set to, and
                          the square shoulder is what tells them apart from a status pill. */}
                      <div className="flex flex-wrap gap-1">
                        {overrideSummary(row).map((part) => (
                          <span
                            key={part}
                            className="rounded-md border bg-muted px-2 py-0.5 text-xs"
                          >
                            {part}
                          </span>
                        ))}
                      </div>
                    </TableCell>
                    <TableCell className="text-right">
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={!row.name || clearing === row.name}
                        aria-label={`Clear every chain override on ${row.label}`}
                        onClick={() => clear(row)}
                      >
                        {clearing === row.name ? "Clearing…" : "Clear override"}
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

const ACTIONS = [
  { value: "set-mode", label: "Set the chain mode" },
  { value: "set-order", label: "Set the vetter order" },
  { value: "set-vetter", label: "Switch a vetter" },
  { value: "clear", label: "Clear overrides" },
] as const;

type ActionKind = (typeof ACTIONS)[number]["value"];

/** Bulk edit wired to the gateway; the editor below is what it draws. */
function BulkCard({
  overrides,
  marketplaces,
  vetters,
}: {
  overrides: MarketplaceOverride[];
  marketplaces: string[];
  vetters: string[];
}) {
  const bulk = useBulkChainSettings();
  const [result, setResult] = useState<BulkChainResult | null>(null);

  const apply = (change: BulkChainChange, action: BulkAction) => {
    setResult(null);
    bulk.mutate(change, {
      onSuccess: (answer) => {
        setResult(answer);
        // A request in which anything was refused is a failure, whatever else succeeded.
        if ((answer.failed ?? 0) > 0) {
          toast.error(
            `${answer.failed} of ${change.marketplaces?.length ?? 0} refused — see the result below`,
          );
        } else {
          toast.success(actionSentence(action, change.marketplaces?.length ?? 0));
        }
      },
      onError: (error) => toast.error(error.message),
    });
  };

  return (
    <BulkEditor
      overrides={overrides}
      marketplaces={marketplaces}
      vetters={vetters}
      onApply={apply}
      pending={bulk.isPending}
      result={result}
    />
  );
}

/**
 * Select marketplaces, choose one change, read exactly what it would do, then apply it.
 *
 * The confirm step is not a courtesy: an estate-wide change is the sharpest form of a control that
 * decides how much evidence stands behind every approval, and the screen that lists the affected
 * marketplaces with their before and after is the last place a mistake is cheap.
 *
 * @Requirements GW_VETTING_0037
 */
export function BulkEditor({
  overrides,
  marketplaces,
  vetters,
  onApply,
  pending = false,
  result = null,
}: {
  overrides: MarketplaceOverride[];
  marketplaces: string[];
  vetters: string[];
  onApply: (change: BulkChainChange, action: BulkAction) => void;
  pending?: boolean;
  result?: BulkChainResult | null;
}) {
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [kind, setKind] = useState<ActionKind>("set-mode");
  const [mode, setMode] = useState<ChainMode>("stop-after-fail");
  const [vetter, setVetter] = useState("");
  const [enabled, setEnabled] = useState(false);
  const [order, setOrder] = useState<string[]>([]);
  const [reason, setReason] = useState("");
  const [confirming, setConfirming] = useState(false);

  // The confirm step closes as soon as an answer comes back: the screen a reader is looking at must
  // be the result, not the plan that produced it.
  useEffect(() => {
    if (result !== null) setConfirming(false);
  }, [result]);

  // The chain decides the switchable names and the arrangement's starting point, so a chain that
  // gained a vetter cannot leave this form offering one that no longer exists.
  useEffect(() => {
    setOrder(vetters);
    setVetter((current) => (vetters.includes(current) ? current : (vetters[0] ?? "")));
  }, [vetters]);

  const names = useMemo(() => [...marketplaces].sort((a, b) => a.localeCompare(b)), [marketplaces]);
  const chosen = useMemo(() => names.filter((name) => selected.has(name)), [names, selected]);
  const allSelected = names.length > 0 && chosen.length === names.length;

  const action: BulkAction =
    kind === "set-mode"
      ? { kind: "set-mode", mode }
      : kind === "set-order"
        ? { kind: "set-order", vetters: order }
        : kind === "set-vetter"
          ? { kind: "set-vetter", vetter, enabled }
          : { kind: "clear" };

  // Mirrors VettingChainBulkService: a selection, an order that names at least one vetter, and for
  // set-vetter a vetter the chain carries. Nothing here is stricter than the server — which names
  // the vetters, so the client mirrors the invariant and leaves the set to the server — and the
  // hint below says what it is waiting for.
  const ready =
    chosen.length > 0 &&
    (kind !== "set-vetter" || vetter !== "") &&
    (kind !== "set-order" || order.length > 0);
  const changes = confirming ? plannedChanges(chosen, overrides, action) : [];

  const apply = () =>
    onApply(
      {
        marketplaces: chosen,
        action: kind,
        ...(kind === "set-mode" ? { mode } : {}),
        ...(kind === "set-order" ? { vetters: order } : {}),
        ...(kind === "set-vetter" ? { vetter, enabled } : {}),
        ...(kind === "clear" ? { clear: ["mode", "order", "vetters"] } : {}),
        ...(reason.trim() ? { reason: reason.trim() } : {}),
      },
      action,
    );

  return (
    <Card>
      <CardHeader>
        <CardTitle>Bulk edit</CardTitle>
        <CardDescription>
          One change, several marketplaces, one audited act: every marketplace it changes gets its
          own ledger entry carrying this note and one correlation id, so an auditor reads them
          together. You see exactly what will change before anything is applied.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <fieldset className="space-y-2">
          <legend className="text-sm leading-none font-medium">Marketplaces</legend>
          {names.length === 0 ? (
            <p className="text-sm text-muted-foreground">No marketplace is registered yet.</p>
          ) : (
            <div className="max-h-56 space-y-1 overflow-y-auto rounded-md border p-2">
              <label className="flex items-center gap-2 text-sm font-medium">
                <Checkbox
                  checked={allSelected}
                  onCheckedChange={(checked) =>
                    setSelected(checked ? new Set(names) : new Set())
                  }
                />
                All marketplaces
              </label>
              {names.map((name) => (
                <label key={name} className="flex items-center gap-2 text-sm">
                  <Checkbox
                    checked={selected.has(name)}
                    onCheckedChange={(checked) =>
                      setSelected((current) => {
                        const next = new Set(current);
                        if (checked) {
                          next.add(name);
                        } else {
                          next.delete(name);
                        }
                        return next;
                      })
                    }
                  />
                  {name}
                </label>
              ))}
            </div>
          )}
        </fieldset>

        <SegmentedGroup
          label="The change"
          value={kind}
          options={ACTIONS}
          onChange={(next) => {
            setKind(next);
            setConfirming(false);
          }}
        />

        {kind === "set-mode" ? (
          <SegmentedGroup
            // Not "When a vetter fails": that is the default chain's control further up the page,
            // and two groups with one name is two controls a reader cannot tell apart.
            label="The mode to apply"
            value={mode}
            options={[
              { value: "run-all" as ChainMode, label: "Run every vetter" },
              { value: "stop-after-fail" as ChainMode, label: "Stop after a failure" },
            ]}
            onChange={setMode}
          />
        ) : null}

        {kind === "set-vetter" ? (
          <div className="space-y-2">
            <SegmentedGroup
              label="Vetter"
              value={vetter}
              options={vetters.map((name) => ({ value: name, label: name }))}
              onChange={setVetter}
            />
            <SegmentedGroup
              label="State"
              value={enabled ? "on" : "off"}
              options={[
                { value: "on", label: "Run it" },
                { value: "off", label: "Switch it off" },
              ]}
              onChange={(next) => setEnabled(next === "on")}
            />
          </div>
        ) : null}

        {kind === "set-order" ? (
          <BulkOrder order={order} onOrder={setOrder} />
        ) : null}

        <div className="space-y-1">
          <Label htmlFor="bulk-reason">Reason (optional)</Label>
          <Input
            id="bulk-reason"
            autoComplete="off"
            value={reason}
            aria-describedby="bulk-hint"
            onChange={(event) => setReason(event.target.value)}
            placeholder="Why is the estate changing?"
          />
          <p id="bulk-hint" className="text-xs text-muted-foreground">
            {chosen.length === 0
              ? "Select at least one marketplace to continue."
              : kind === "set-vetter" && vetter === ""
                ? "Choose the vetter to switch."
                : kind === "set-order" && order.length === 0
                  ? "The chain has no vetters to arrange."
                  : `${chosen.length} selected. The next step lists what each one would change from and to.`}
          </p>
        </div>

        {confirming ? (
          <div className="space-y-3 rounded-md border bg-muted/40 p-3">
            <p className="text-sm font-medium">{actionSentence(action, chosen.length)}</p>
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Marketplace</TableHead>
                    <TableHead>Now</TableHead>
                    <TableHead>After</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {changes.map((change) => (
                    <TableRow key={change.marketplace}>
                      <TableCell className="font-medium">{change.marketplace}</TableCell>
                      <TableCell className="text-muted-foreground">{change.before}</TableCell>
                      <TableCell>{change.after}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
            <div className="flex flex-wrap gap-2">
              <Button size="sm" disabled={pending} onClick={apply}>
                {pending ? "Applying…" : "Apply to these marketplaces"}
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={pending}
                onClick={() => setConfirming(false)}
              >
                Go back
              </Button>
            </div>
          </div>
        ) : (
          // The hint above is what explains the disabled state, so it is bound to the control the
          // state belongs to and not only to the field it sits under.
          <Button
            size="sm"
            disabled={!ready}
            aria-describedby="bulk-hint"
            onClick={() => setConfirming(true)}
          >
            Review the change
          </Button>
        )}

        {result ? <BulkResult result={result} /> : null}
      </CardContent>
    </Card>
  );
}

/**
 * The arrangement a bulk order change would write. The same named movement controls the
 * per-marketplace list uses, for the same reason: an ordering gesture with no keyboard equivalent
 * is a control some readers do not have.
 */
function BulkOrder({ order, onOrder }: { order: string[]; onOrder: (next: string[]) => void }) {
  const [moved, setMoved] = useState<{ vetter: string; position: number } | null>(null);
  const move = (index: number, by: number) => {
    const next = [...order];
    const target = index + by;
    if (target < 0 || target >= next.length) return;
    const vetter = next[index]!;
    [next[index], next[target]] = [next[target]!, vetter];
    onOrder(next);
    setMoved({ vetter, position: target + 1 });
  };
  return (
    <div className="space-y-2">
      <p className="text-sm leading-none font-medium">The order they would run in</p>
      <p aria-live="polite" className="sr-only">
        {moved === null ? "" : `${moved.vetter} moved to position ${moved.position} of ${order.length}`}
      </p>
      <ol aria-label="Vetter order to apply">
        {order.map((vetter, index) => (
          <li key={vetter} className="flex items-center gap-2 border-b py-1.5 last:border-b-0">
            <span className="w-6 text-xs text-muted-foreground tabular-nums">{index + 1}</span>
            <span className="min-w-0 flex-1 truncate text-sm">{vetter}</span>
            {/* The same silhouette as the per-marketplace list: an icon button per movement,
                named for assistive technology, so the two orderings read as one control. */}
            <Button
              type="button"
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
    </div>
  );
}

/**
 * What the request actually did, marketplace by marketplace. The headline is the failure whenever
 * there is one: a summary that led with the successes would be the partial failure reported as a
 * success.
 */
function BulkResult({ result }: { result: BulkChainResult }) {
  const failed = result.failed ?? 0;
  return (
    <div
      className="space-y-2 rounded-md border p-3"
      role={failed > 0 ? "alert" : "status"}
    >
      <p className={failed > 0 ? "text-sm font-medium text-destructive" : "text-sm font-medium"}>
        {failed > 0
          ? `${failed} refused, ${result.applied ?? 0} applied, ${result.unchanged ?? 0} already as asked`
          : `${result.applied ?? 0} applied, ${result.unchanged ?? 0} already as asked`}
      </p>
      <div className="overflow-x-auto">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Marketplace</TableHead>
              <TableHead>Outcome</TableHead>
              <TableHead>Detail</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {(result.results ?? []).map((outcome) => (
              <TableRow key={outcome.marketplace}>
                <TableCell className="font-medium">{outcome.marketplace}</TableCell>
                <TableCell>
                  <Badge
                    variant={
                      outcome.status === "failed"
                        ? "destructive"
                        : outcome.status === "applied"
                          ? "default"
                          : "secondary"
                    }
                  >
                    {(outcome.status ?? "").toLowerCase()}
                  </Badge>
                </TableCell>
                <TableCell className="text-muted-foreground">{outcome.detail}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      <p className="text-xs text-muted-foreground">
        Correlation id <span className="font-mono">{result.correlationId}</span> — every ledger entry
        this act wrote carries it.
      </p>
    </div>
  );
}
