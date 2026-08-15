// Aligns Playwright's junit classname ("portal.spec.ts") with the FQN style
// @reqstool/reqstool-typescript-tags emits ("portal"), so reqstool can match
// @SVCs-tagged e2e tests to their results (fqn = classname + "." + name).
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const uiDir = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const file = path.join(uiDir, "test-results", "playwright-junit.xml");

if (!fs.existsSync(file)) {
  console.error(`missing ${file}`);
  process.exit(1);
}

const xml = fs
  .readFileSync(file, "utf8")
  .replace(/classname="([^"]+?)\.(?:spec|test)\.(?:ts|tsx)"/g, (_, base) =>
    `classname="${base.replaceAll("/", ".")}"`,
  );
fs.writeFileSync(file, xml);
console.log(`normalized classnames in ${file}`);
