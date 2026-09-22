// Aligns junit classnames with the FQN style @reqstool/reqstool-typescript-tags
// emits, so reqstool can match @SVCs-tagged tests to their results
// (fqn = classname + "." + name): Playwright's "portal.spec.ts" becomes "portal",
// and vitest's "src/pages/marketplace-detail.test.tsx" becomes
// "pages.marketplace-detail". Defaults to the Playwright report; pass paths
// relative to test-results/ to normalize others.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const uiDir = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const names = process.argv.length > 2 ? process.argv.slice(2) : ["playwright-junit.xml"];

for (const name of names) {
  const file = path.join(uiDir, "test-results", name);
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
      /classname="(?:src\/)?([^"]+?)\.(?:spec|test)\.(?:ts|tsx)"/g,
      (_, base) => `classname="${base.replaceAll("/", ".")}"`,
    ),
  );
  console.log(`normalized classnames in ${file}`);
}
