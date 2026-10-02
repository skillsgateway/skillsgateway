#!/usr/bin/env bash
# Manual mutation run for vet-hook-modules (evidence.md, "Mutation").
# Applies each mutant alone, runs the tests that must kill it, restores, and proves the restore
# with `git diff --exit-code`. Fails closed: a mutant that does not apply, a survivor, a run with
# no test report, or a dirty tree after restore stops the run with a nonzero exit.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
git diff --quiet || { echo "tree is dirty; commit first"; exit 2; }
trap 'git checkout -- src/main' EXIT

V=src/main/java/dev/skillsgateway/server/vetting
I=src/main/java/dev/skillsgateway/server/ingestion

mutate() { # file old new: replace exactly one occurrence, or fail
  python3 - "$1" "$2" "$3" <<'PY'
import sys
path, old, new = sys.argv[1:]
text = open(path).read()
if text.count(old) != 1:
    sys.exit(f"mutant does not apply exactly once in {path}: {old!r}")
open(path, "w").write(text.replace(old, new))
PY
}

# A kill counts only when the named test method fails; no report means INVALID, never a kill.
java_killed() { # id test-class expected-method
  local report=target/surefire-reports/TEST-dev.skillsgateway.server.$2.xml
  rm -f "$report"
  if ./mvnw -q -o -Dskip.ui.verify=true -Dskip.installnodepnpm -Dskip.pnpm -Djacoco.skip=true \
      -Dtest="${2##*.}" -Dsurefire.failIfNoSpecifiedTests=false test > "/tmp/mutant-$1.log" 2>&1; then
    return 1
  fi
  [ -f "$report" ] || { echo "$1: INVALID (no test report; see /tmp/mutant-$1.log)"; exit 4; }
  python3 - "$report" "$3" <<'PY' || { echo "$1: INVALID ($3 did not fail)"; exit 4; }
import sys, xml.etree.ElementTree as ET
report, method = sys.argv[1:]
for case in ET.parse(report).getroot().iter("testcase"):
    if case.get("name", "").startswith(method) and (case.find("failure") is not None or case.find("error") is not None):
        sys.exit(0)
sys.exit(1)
PY
}

run() { # id kind test-class expected file old new
  local id=$1 kind=$2 cls=$3 expected=$4 file=$5
  # ONLY=<regex> runs a subset (the whole set exceeds one tool-call timeout); unset runs all.
  if [[ -n "${ONLY:-}" && ! $id =~ $ONLY ]]; then return 0; fi
  mutate "$file" "$6" "$7"
  # DRY=1 proves every mutant applies exactly once, without running a test.
  if [[ -n "${DRY:-}" ]]; then git checkout -- "$file"; echo "$id applies"; return 0; fi
  if "${kind}_killed" "$id" "$cls" "$expected"; then result=killed; else result=SURVIVED; fi
  git checkout -- "$file"
  git diff --exit-code > /dev/null || { echo "$id: restore left the tree dirty"; exit 3; }
  echo "$id $result"
  [ "$result" = killed ] || exit 1
}


H=ingestion.HookModulesTests
T=vetting.ExecutableSurfaceVetterTests
X=ExecutableSurfaceTests
M="$I/HookModules.java"
C="$I/PluginComponents.java"
E="$V/ExecutableSurfaceVetter.java"

run M1-comments-kept java $H aModuleIsListedWithItsEventsUsesImportsAndWhatCouldNotBeScanned "$M" \
  "if (c == '/' && next == '/') {" "if (false) {"
run M2-strings-not-protected java $H aModuleIsListedWithItsEventsUsesImportsAndWhatCouldNotBeScanned "$M" \
  "} else if (c == '\\'' || c == '\"' || c == '\`') {" "} else if (false) {"
run M3-imports-not-followed java $H aModuleIsListedWithItsEventsUsesImportsAndWhatCouldNotBeScanned "$M" \
  'if (target != null && visited.add(target)) {' 'if (false) {'
run M4-package-taken-for-a-path java $H aModuleIsListedWithItsEventsUsesImportsAndWhatCouldNotBeScanned "$M" \
  'if (!specifier.startsWith("./") && !specifier.startsWith("../")) {' 'if (false) {'
run M5-unknown-key-silent java $H anUnrecognisedKeyIsOneProblemNamingIt "$C" \
  'unrecognised(field.getKey(), doc, pointer);' ';'
run M6-description-reported java $H aRecognisedShapeIsNoProblem "$C" \
  'case "description" -> v.isTextual();' 'case "description" -> false;'
run M7-any-array-is-an-event java $H anUnrecognisedKeyInAnInlinePluginManifestHooksObjectIsAProblem "$C" \
  '.allMatch(JsonNode::isObject);' '.allMatch(node -> true);'
run M8-escaping-module-read java $H aModulePathEscapingThePluginRootIsAProblemNotAModule "$C" \
  'if (path == null || !(root.isEmpty() || path.startsWith(root + "/"))) {' 'if (path == null) {'
run M9-prompt-compose-not-steering java $T aModuleThatSteersTheAgentRunsProgramsOrCallsModelsIsFlaggedForEach "$E" \
  '"prompt.compose", "rewrite the system prompt");' '"prompt.submit", "rewrite the system prompt");'
run M10-model-call-medium java $T aModuleThatSteersTheAgentRunsProgramsOrCallsModelsIsFlaggedForEach "$E" \
  'Severity.LOW,' 'Severity.MEDIUM,'
run M11-module-files-unscanned java $T downloadAndExecuteInAModuleOrAnImportBlocks "$E" \
  'for (String file : module.files()) {' 'for (String file : List.<String>of()) {'
run M12-file-scanned-twice java $T aFileBothAHookAndAModuleLoadIsScannedOnce "$E" \
  'if (!scanned.add(file)) {' 'if (!scanned.add(file) && false) {'
run M13-unscanned-unreported java $T aModuleOrImportThatCannotBeReadIsReported "$E" \
  'for (PluginComponents.Problem problem : module.unscanned()) {' 'for (PluginComponents.Problem problem : List.<PluginComponents.Problem>of()) {'
run M14-module-reported-as-hook java $X aHookModuleIsHeldWithItsFindingsAndItsImportedFetchBlocksUntilWaived "$E" \
  'static final String AUTO_RUN_MODULE = "auto-run-module";' 'static final String AUTO_RUN_MODULE = "auto-run-hook";'
if [[ -n "${DRY:-}" ]]; then echo "dry run: every mutant applies"; else echo "all mutants killed"; fi
