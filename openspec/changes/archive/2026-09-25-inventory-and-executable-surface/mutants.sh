#!/usr/bin/env bash
# Manual mutation run for inventory-and-executable-surface (evidence.md, "Mutation").
# Applies each mutant alone, runs the tests that must kill it, restores, and proves the restore
# with `git diff --exit-code`. Fails closed: a mutant that does not apply, a survivor, a run with
# no test report, or a dirty tree after restore stops the run with a nonzero exit.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
git diff --quiet || { echo "tree is dirty; commit first"; exit 2; }
trap 'git checkout -- src/main' EXIT

V=src/main/java/dev/skillsgateway/server/vetting
I=src/main/java/dev/skillsgateway/server/ingestion
WEB=src/main/frontend/src/components/snapshot-inventory.tsx

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

ui_killed() { # id unused expected-test
  if (cd src/main/frontend && npx vitest run --project unit src/components/snapshot-inventory.test.tsx > "/tmp/mutant-$1.log" 2>&1); then
    return 1
  fi
  grep -q "× $3" "/tmp/mutant-$1.log" || { echo "$1: INVALID ($3 did not fail)"; exit 4; }
}

run() { # id kind test-class expected file old new
  local id=$1 kind=$2 cls=$3 expected=$4 file=$5
  # ONLY=<regex> runs a subset (the whole set exceeds one tool-call timeout); unset runs all.
  if [[ -n "${ONLY:-}" && ! $id =~ $ONLY ]]; then return 0; fi
  mutate "$file" "$6" "$7"
  if "${kind}_killed" "$id" "$cls" "$expected"; then result=killed; else result=SURVIVED; fi
  git checkout -- "$file"
  git diff --exit-code > /dev/null || { echo "$id: restore left the tree dirty"; exit 3; }
  echo "$id $result"
  [ "$result" = killed ] || exit 1
}

T=vetting.ExecutableSurfaceVetterTests
run M1-no-pipe-rule java $T aHookCommandThatDownloadsAndExecutesBlocks "$V/RuntimeFetch.java" \
  'if (PIPE.matcher(normal).find()) {' 'if (false) {'
run M2-comments-scanned java $T aLaunchedScriptWithoutDownloadAndExecuteStaysSilent "$V/RuntimeFetch.java" \
  'if (file && COMMENT.matcher(line.text()).find()) {' 'if (false) {'
run M3-any-chmod-covers-any-download java $T theTrialLauncherShapeBlocksAtItsDownloadLinesOnceForEveryHookReachingIt "$V/RuntimeFetch.java" \
  'unnamed || targets.contains(download);' 'unnamed || !targets.isEmpty();'
run M4-quoting-not-removed java $T aHookCommandThatDownloadsAndExecutesBlocks "$V/RuntimeFetch.java" \
  'normal = WORD_QUOTE.matcher(normal).replaceAll("");' 'normal = normal.strip();'
run M5-no-transitive-follow java $T aScriptInASubdirectoryReachedThroughASecondScriptIsFollowed "$V/ExecutableSurfaceVetter.java" \
  'static final int MAX_DEPTH = 3;' 'static final int MAX_DEPTH = 1;'
run M6-hook-is-info java $T everyHookWarnsWithItsTriggerAtItsDeclaration "$V/ExecutableSurfaceVetter.java" \
  'new Finding("auto-run-hook", Severity.MEDIUM,' 'new Finding("auto-run-hook", Severity.INFO,'
run M7-unscanned-target-silent java $T aBinaryOrOversizedFileAHookRunsIsReported "$V/ExecutableSurfaceVetter.java" \
  'if (launched.depth() == 1) {' 'if (false) {'
run M8-no-install-still-fetches java $T aLaunchedScriptWithoutDownloadAndExecuteStaysSilent "$V/RuntimeFetch.java" \
  '(?![^\\n]*--(?:no-install|offline))' ''
run M9-manifest-only-roots java $T aPluginTheManifestDoesNotListIsStillVetted "$V/ExecutableSurfaceVetter.java" \
  'if (path.equals(PLUGIN_JSON) || path.endsWith("/" + PLUGIN_JSON)) {' 'if (false) {'
run M10-plugin-json-hooks-ignored java ingestion.PluginComponentsTests hooksMergeFromTheDefaultFileAManifestPathInlineAndArrayForms "$I/PluginComponents.java" \
  'reader.hookDeclaration(plugin.root().path("hooks"), plugin, "/hooks", hooks);' ';'
run M11-escape-allowed java ingestion.PluginComponentsTests sourcesNormalizeToRoots "$I/PluginComponents.java" \
  $'if (segments.isEmpty()) {\n                    return null;' $'if (segments.isEmpty()) {\n                    continue;'
run M12-open-by-default ui - each_component_kind_is_a_collapsed_count_that_expands_in_place "$WEB" \
  'useState<ReadonlySet<Kind>>(new Set())' 'useState<ReadonlySet<Kind>>(new Set(KINDS.map(({ kind }) => kind)))'
echo "all mutants killed"
