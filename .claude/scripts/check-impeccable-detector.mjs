#!/usr/bin/env node
/**
 * Guard: the Impeccable detector degrades silently.
 *
 * Its HTML engine imports htmlparser2, css-select, css-tree and domutils. When they
 * are absent it does not fail — it falls back to regex matching and writes one line
 * to stderr. The hook never reads the detector's stderr, so a developer whose skill
 * dependencies are missing gets confident "no findings" reminders while computed
 * contrast, CSS custom properties and selector matching are all switched off. A
 * clean report and a broken detector look identical.
 *
 * Node resolves those bare imports by walking up from the detector's own directory,
 * so they must live in .claude/skills/impeccable/node_modules — the portal's
 * node_modules is on a different branch of the tree and cannot satisfy them.
 *
 * Exits 0 always: this is a developer convenience, never a reason to fail a session
 * or a build. It is silent when healthy and loud when not.
 */
import { createRequire } from "node:module";
import { existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const REQUIRED = ["htmlparser2", "css-select", "css-tree", "domutils"];

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const skillDir = join(repoRoot, ".claude", "skills", "impeccable");

// Nothing to guard if the skill is not vendored in this checkout.
if (!existsSync(join(skillDir, "SKILL.md"))) process.exit(0);

// Resolve exactly where the detector does, rather than from this script.
const require = createRequire(join(skillDir, "scripts", "detector", "engines", "static-html", "detect-html.mjs"));
const missing = REQUIRED.filter((name) => {
  try {
    require.resolve(name);
    return false;
  } catch {
    return true;
  }
});

if (missing.length === 0) process.exit(0);

if (process.argv.includes("--fix")) {
  process.stderr.write("Installing the Impeccable detector's dependencies…\n");
  const result = spawnSync("npm", ["install", "--no-audit", "--no-fund"], { cwd: skillDir, stdio: "inherit" });
  process.exit(result.status === 0 ? 0 : 0);
}

process.stderr.write(
  `\n⚠️  Impeccable detector is DEGRADED — missing: ${missing.join(", ")}\n` +
    `   Design findings are an undercount, not a clean bill of health:\n` +
    `   computed contrast, CSS custom properties and selector matching are NOT evaluated.\n` +
    `   Fix:  npm --prefix .claude/skills/impeccable install\n\n`,
);
process.exit(0);
