// Aligns Playwright's junit classname ("portal.spec.ts") with the FQN style
// @reqstool/reqstool-typescript-tags emits ("portal"), so reqstool can match
// @SVCs-tagged e2e tests to their results (fqn = classname + "." + name).
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const uiDir = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const file = path.join(uiDir, "test-results", "playwright-junit.xml");

// Read first and report on failure, rather than testing for existence and then
// reading: the check-then-act pair is a race, and the read already tells us.
let xml;
try {
  xml = fs.readFileSync(file, "utf8");
} catch (error) {
  console.error(`cannot read ${file}: ${error.message}`);
  process.exit(1);
}

fs.writeFileSync(
  file,
  xml.replace(
    /classname="([^"]+?)\.(?:spec|test)\.(?:ts|tsx)"/g,
    (_, base) => `classname="${base.replaceAll("/", ".")}"`,
  ),
);
console.log(`normalized classnames in ${file}`);
