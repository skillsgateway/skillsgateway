#!/usr/bin/env node
// Screenshots Storybook stories from a static build, for before/after images on a portal PR.
//
//   node .claude/skills/ui-screenshots/scripts/capture-stories.mjs --storybook <storybook-static dir> --out <dir> --prefix before \
//        [--widths 1280,390] [--themes light,dark] <story-id> [<story-id> ...]
//
// Writes <out>/<prefix>-<story-id>-<theme>-<width>.png, one per story, theme and width, of the
// story's root element only. Playwright comes from the frontend's own node_modules. Fails closed:
// an unknown story, a story that renders an error, or an empty root is an error, not a blank image.
import { createServer } from "node:http";
import { createReadStream, existsSync, mkdirSync, statSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, extname, join, resolve } from "node:path";


function parse(argv) {
  const options = { widths: [1280, 390], themes: ["light", "dark"], stories: [], frontend: null };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    const value = () => {
      const next = argv[++i];
      if (next === undefined) throw new Error(`${arg} needs a value`);
      return next;
    };
    if (arg === "--storybook") options.storybook = resolve(value());
    else if (arg === "--out") options.out = resolve(value());
    else if (arg === "--prefix") options.prefix = value();
    else if (arg === "--widths") options.widths = value().split(",").map(Number);
    else if (arg === "--themes") options.themes = value().split(",");
    else if (arg === "--frontend") options.frontend = resolve(value());
    else if (arg.startsWith("--")) throw new Error(`unknown option ${arg}`);
    else options.stories.push(arg);
  }
  for (const required of ["storybook", "out", "prefix"]) {
    if (!options[required]) throw new Error(`--${required} is required`);
  }
  if (options.stories.length === 0) throw new Error("name at least one story id");
  if (!existsSync(join(options.storybook, "iframe.html"))) {
    throw new Error(`${options.storybook} is not a Storybook static build (no iframe.html)`);
  }
  return options;
}

/** The frontend whose node_modules hold Playwright: given, or found above the current directory. */
function frontendDir(given) {
  if (given) return given;
  for (let dir = process.cwd(); dir !== dirname(dir); dir = dirname(dir)) {
    const candidate = join(dir, "src/main/frontend");
    if (existsSync(join(candidate, "node_modules/playwright"))) return candidate;
    if (existsSync(join(dir, "node_modules/playwright")) && existsSync(join(dir, ".storybook"))) return dir;
  }
  throw new Error("cannot find the frontend's node_modules/playwright; pass --frontend src/main/frontend");
}

const TYPES = { ".html": "text/html", ".js": "text/javascript", ".mjs": "text/javascript", ".css": "text/css",
  ".json": "application/json", ".svg": "image/svg+xml", ".png": "image/png", ".woff2": "font/woff2" };

function serve(root) {
  const server = createServer((request, response) => {
    const path = decodeURIComponent(new URL(request.url, "http://x").pathname);
    const file = join(root, path === "/" ? "index.html" : path);
    if (!file.startsWith(root) || !existsSync(file) || statSync(file).isDirectory()) {
      response.writeHead(404).end();
      return;
    }
    response.writeHead(200, { "content-type": TYPES[extname(file)] ?? "application/octet-stream" });
    createReadStream(file).pipe(response);
  });
  return new Promise((ok) => server.listen(0, "127.0.0.1", () => ok(server)));
}

async function main() {
  const options = parse(process.argv.slice(2));
  const require = createRequire(join(frontendDir(options.frontend), "package.json"));
  const { chromium } = require("playwright");
  mkdirSync(options.out, { recursive: true });
  const server = await serve(options.storybook);
  const base = `http://127.0.0.1:${server.address().port}`;
  const browser = await chromium.launch();
  const written = [];
  try {
    for (const story of options.stories) {
      for (const theme of options.themes) {
        for (const width of options.widths) {
          const page = await browser.newPage({ viewport: { width, height: 900 }, deviceScaleFactor: 2 });
          const errors = [];
          page.on("pageerror", (error) => errors.push(error.message));
          await page.goto(`${base}/iframe.html?id=${encodeURIComponent(story)}&viewMode=story`);
          const root = page.locator("#storybook-root");
          try {
            await root.locator(":scope > *").first().waitFor({ timeout: 15000 });
          } catch {
            throw new Error(`story ${story} rendered nothing in 15 s: is the id right? (see index.json in the build)`);
          }
          // Storybook shows its own error panel for an unknown story or a render error.
          if (await page.locator(".sb-show-errordisplay, #error-message:not(:empty)").count()) {
            throw new Error(`story ${story} did not render: ${await page.locator("#error-message").innerText()}`);
          }
          if (theme === "dark") await page.evaluate(() => document.documentElement.classList.add("dark"));
          await page.waitForLoadState("networkidle");
          await page.waitForTimeout(300); // let a play function's final state and focus settle
          if (errors.length) throw new Error(`story ${story} threw: ${errors.join("; ")}`);
          const file = join(options.out, `${options.prefix}-${story}-${theme}-${width}.png`);
          await root.screenshot({ path: file });
          written.push(file);
          await page.close();
        }
      }
    }
  } finally {
    await browser.close();
    server.close();
  }
  for (const file of written) console.log(file);
}

main().catch((error) => {
  console.error(`capture-stories: ${error.message}`);
  process.exit(1);
});
