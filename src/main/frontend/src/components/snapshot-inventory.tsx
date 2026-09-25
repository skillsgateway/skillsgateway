import { ChevronRight, Puzzle } from "lucide-react";
import { Fragment, useId, useState } from "react";
import { useSnapshotContent, type SnapshotContent } from "@/api/queries";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

type Plugin = NonNullable<SnapshotContent["plugins"]>[number];
type Hook = NonNullable<Plugin["hooks"]>[number];
type Kind = "skills" | "commands" | "agents" | "hooks" | "mcpServers";

const KINDS: readonly { kind: Kind; one: string; many: string }[] = [
  { kind: "skills", one: "skill", many: "skills" },
  { kind: "commands", one: "command", many: "commands" },
  { kind: "agents", one: "agent", many: "agents" },
  { kind: "hooks", one: "hook", many: "hooks" },
  { kind: "mcpServers", one: "MCP server", many: "MCP servers" },
];

function count(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

/** A hook: when it fires, what declared it, and what it runs. */
function HookRow({ hook }: { hook: Hook }) {
  const trigger = hook.matcher ? `${hook.event} · ${hook.matcher}` : hook.event;
  return (
    <li className="space-y-1 rounded-md border p-2 text-xs">
      <div className="flex flex-wrap items-center gap-2">
        <span className="rounded-md border bg-muted px-2 py-0.5 font-medium">{trigger}</span>
        <span className="text-muted-foreground">
          {hook.type}
          {hook.declaredBy && hook.declaredBy !== "plugin" ? ` · declared by ${hook.declaredBy}` : null}
        </span>
      </div>
      {hook.runs ? <code className="block font-mono break-all">{hook.runs}</code> : null}
      <span className="block font-mono break-all text-muted-foreground">{hook.location}</span>
    </li>
  );
}

/** The expanded list of one kind. */
function KindList({ plugin, kind }: { plugin: Plugin; kind: Kind }) {
  if (kind === "skills") {
    return (
      <div className="flex flex-wrap gap-2">
        {(plugin.skills ?? []).map((skill) => (
          <Badge key={skill.path} variant="outline">
            {skill.name}
          </Badge>
        ))}
      </div>
    );
  }
  if (kind === "hooks") {
    return (
      <ul className="space-y-2">
        {(plugin.hooks ?? []).map((hook, index) => (
          // Two identical hooks on one minified line share every field, so the index keeps keys unique.
          <HookRow key={`${index}:${hook.location}`} hook={hook} />
        ))}
      </ul>
    );
  }
  return (
    <ul className="space-y-1 text-sm">
      {(plugin[kind] ?? []).map((component, index) => (
        <li key={`${index}:${component.path}`} className="flex flex-wrap items-baseline gap-2">
          <span className="font-medium">{component.name}</span>
          <span className="font-mono text-xs break-all text-muted-foreground">{component.path}</span>
        </li>
      ))}
    </ul>
  );
}

/**
 * One plugin's components as one count per kind it has, all collapsed; each count expands its own
 * list in place. A hook runs without anyone invoking it, so its trigger and command are what a
 * reviewer reads.
 *
 * @Requirements GW_INGEST_0045, GW_INGEST_0046
 */
export function PluginInventory({ plugin }: { plugin: Plugin }) {
  const id = useId();
  const [open, setOpen] = useState<ReadonlySet<Kind>>(new Set());
  const present = KINDS.filter(({ kind }) => (plugin[kind] ?? []).length > 0);
  const toggle = (kind: Kind) =>
    setOpen((current) => {
      const next = new Set(current);
      if (next.has(kind)) next.delete(kind);
      else next.add(kind);
      return next;
    });

  return (
    <div className="rounded-md border p-3">
      <div className="flex flex-wrap items-center gap-2 font-medium">
        <Puzzle className="size-4 shrink-0 text-primary" aria-hidden />
        <span className="break-all">{plugin.name}</span>
        <span className="font-mono text-xs break-all text-muted-foreground">{plugin.source}</span>
      </div>
      {plugin.description ? (
        <p className="mt-1 text-sm text-muted-foreground">{plugin.description}</p>
      ) : null}
      {present.length === 0 ? (
        <p className="mt-2 text-xs text-muted-foreground">no components found</p>
      ) : (
        <>
          <div className="mt-2 flex flex-wrap items-center gap-1 text-muted-foreground">
            {present.map(({ kind, one, many }, index) => (
              <Fragment key={kind}>
                {index > 0 ? (
                  <span aria-hidden className="text-xs">
                    ·
                  </span>
                ) : null}
                <Button
                  variant="ghost"
                  size="xs"
                  aria-expanded={open.has(kind)}
                  aria-controls={`${id}-${kind}`}
                  onClick={() => toggle(kind)}
                >
                  <ChevronRight
                    aria-hidden
                    className={`transition-transform motion-reduce:transition-none ${open.has(kind) ? "rotate-90" : ""}`}
                  />
                  {count((plugin[kind] ?? []).length, one, many)}
                </Button>
              </Fragment>
            ))}
          </div>
          {present.map(({ kind, many }) => (
            <div
              key={kind}
              id={`${id}-${kind}`}
              role="region"
              aria-label={`${many} of ${plugin.name}`}
              hidden={!open.has(kind)}
              className="mt-2"
            >
              <KindList plugin={plugin} kind={kind} />
            </div>
          ))}
        </>
      )}
    </div>
  );
}

/**
 * Everything the snapshot ships, plugin by plugin.
 *
 * @Requirements GW_INGEST_0008, GW_INGEST_0046
 */
export function SnapshotInventory({ snapshotId }: { snapshotId: number }) {
  const content = useSnapshotContent(snapshotId);
  let body: React.ReactNode;
  if (content.isLoading) body = <p className="text-sm text-muted-foreground">Loading contents…</p>;
  else if (content.isError)
    body = (
      <p role="alert" className="text-sm text-destructive">
        {content.error.message}
      </p>
    );
  else if ((content.data?.plugins ?? []).length === 0)
    body = <p className="text-sm text-muted-foreground">No plugins declared in this snapshot.</p>;
  else
    body = (content.data?.plugins ?? []).map((plugin) => (
      <PluginInventory key={`${plugin.name}:${plugin.source}`} plugin={plugin} />
    ));
  return (
    <section aria-label={`Contents of snapshot ${snapshotId}`} className="space-y-3">
      {body}
    </section>
  );
}
