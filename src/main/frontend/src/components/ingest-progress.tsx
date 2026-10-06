import { Check, Loader2 } from "lucide-react";
import { useEffect, useState } from "react";
import type { IngestStatus } from "@/api/queries";
import { formatElapsed } from "@/lib/datetime";
import { cn } from "@/lib/utils";

type Running = NonNullable<IngestStatus["running"]>;

const STAGES = [
  { id: "queued", label: "Queued" },
  { id: "fetching", label: "Fetching" },
  { id: "evaluating-manifest", label: "Evaluating manifest" },
  { id: "vetting", label: "Vetting" },
] as const;

function stageLabel(stage: Running["stage"]) {
  return STAGES.find((s) => s.id === stage)?.label ?? stage ?? "Unknown";
}

function useNow(ticking: boolean) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!ticking) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [ticking]);
  return now;
}

/**
 * An ingest in progress: the stages it passes through, the one it is at, and how long it has run
 * (GW_INGEST_0068). Only the stage is announced; the elapsed time ticks visibly but silently. An
 * ingest whose gateway instance stopped is said to be interrupted, not shown as running (GW_INGEST_0069).
 *
 * @Requirements GW_INGEST_0068, GW_INGEST_0069
 */
export function IngestProgress({ running, now: fixedNow }: { running: Running; now?: number }) {
  const ticking = fixedNow === undefined && !running.interrupted;
  const clock = useNow(ticking);
  const now = fixedNow ?? clock;
  const elapsed = running.startedAt ? formatElapsed(now - Date.parse(running.startedAt)) : null;

  if (running.interrupted) {
    return (
      <p data-testid="ingest-interrupted" role="alert" className="break-words text-sm text-destructive">
        Ingest interrupted while {stageLabel(running.stage).toLowerCase()}: the gateway instance running it
        stopped. Start it again with Ingest.
      </p>
    );
  }

  const current = STAGES.findIndex((s) => s.id === running.stage);
  return (
    <div data-testid="ingest-progress" className="space-y-1.5">
      <p className="sr-only" aria-live="polite">
        Ingest stage: {stageLabel(running.stage)}
      </p>
      <p className="text-sm">
        Ingesting
        {elapsed ? (
          <>
            {" · "}
            <span data-testid="ingest-elapsed" className="font-mono tabular-nums text-muted-foreground">
              {elapsed}
            </span>
          </>
        ) : null}
      </p>
      <ol aria-label="Ingest stages" className="flex flex-wrap items-center gap-1.5">
        {STAGES.map((stage, index) => {
          const done = index < current;
          const active = index === current;
          return (
            <li
              key={stage.id}
              aria-current={active ? "step" : undefined}
              className={cn(
                "inline-flex items-center gap-1 rounded-md border px-2 py-0.5 text-xs",
                active && "border-primary text-primary",
                done && "text-muted-foreground",
                !active && !done && "border-dashed text-muted-foreground",
              )}
            >
              {done ? <Check aria-hidden className="size-3" /> : null}
              {active ? <Loader2 aria-hidden className="size-3 motion-safe:animate-spin" /> : null}
              {stage.label}
              {done ? <span className="sr-only"> (done)</span> : null}
            </li>
          );
        })}
      </ol>
    </div>
  );
}
