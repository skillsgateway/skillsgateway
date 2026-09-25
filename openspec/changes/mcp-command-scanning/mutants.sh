#!/usr/bin/env bash
# Manual mutation run for mcp-command-scanning (evidence.md, "Mutation").
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
  if "${kind}_killed" "$id" "$cls" "$expected"; then result=killed; else result=SURVIVED; fi
  git checkout -- "$file"
  git diff --exit-code > /dev/null || { echo "$id: restore left the tree dirty"; exit 3; }
  echo "$id $result"
  [ "$result" = killed ] || exit 1
}

T=vetting.ExecutableSurfaceVetterTests
run M1-mcp-runner-blocks java $T anMcpServerThatRunsAPackageRunnerWarnsAtItsCommand "$V/ExecutableSurfaceVetter.java" \
  $'                            runner ? Severity.MEDIUM : Severity.HIGH,' $'                            Severity.HIGH,'
run M2-wrapper-script-not-scanned java $T anMcpServerThatRunsAPackageRunnerWarnsAtItsCommand "$V/ExecutableSurfaceVetter.java" \
  'if (script != null) {' 'if (false) {'
run M3-defaults-not-expanded java $T anMcpServerThatRunsAPackageRunnerWarnsAtItsCommand "$V/ExecutableSurfaceVetter.java" \
  'words.add(withDefaults(server.command()));' 'words.add(server.command());'
run M4-env-not-dropped java $T anMcpServerThatRunsAPackageRunnerWarnsAtItsCommand "$V/ExecutableSurfaceVetter.java" \
  '            dropEnv(words);' ''
run M5-local-runner-flagged java $T aLocalRunnerALocalBinaryARemoteServerAndAPrintedDownloadStaySilent "$V/ExecutableSurfaceVetter.java" \
  'if (runner && localRunner && match.line() == 1) {' 'if (false) {'
run M6-launched-not-followed java $T aScriptAnMcpServerLaunchesIsFollowedThroughASecondScript "$V/ExecutableSurfaceVetter.java" \
  'follow(text, root, server.location(), true);' ';'
run M7-launched-fetch-medium java $T aScriptAnMcpServerLaunchesIsFollowedThroughASecondScript "$V/ExecutableSurfaceVetter.java" \
  $'                        runner ? Severity.MEDIUM : Severity.HIGH,' $'                        Severity.MEDIUM,'
run M8-one-visited-set java $T aScriptBothAHookAndAnMcpServerLaunchKeepsTheHooksHighFinding "$V/ExecutableSurfaceVetter.java" \
  'Set<String> visited = mcp ? mcpScanned : scanned;' 'Set<String> visited = scanned;'
run M9-launched-scanned-as-hook java $T aLocalRunnerALocalBinaryARemoteServerAndAPrintedDownloadStaySilent "$V/ExecutableSurfaceVetter.java" \
  'String content = mcp ? mcpLaunchedText(next) : launchedText(next);' 'String content = launchedText(next);'
run M10-remote-scanned java ingestion.PluginComponentsTests localMcpServerCommandsAreReadFromEverySourceAtTheirCommandLine "$I/PluginComponents.java" \
  'if ("stdio".equals(type) && server.path("command").isTextual()) {' 'if (server.path("command").isTextual()) {'
run M11-absolute-runner-kept java $T anMcpServerThatRunsAPackageRunnerWarnsAtItsCommand "$V/ExecutableSurfaceVetter.java" \
  'if (words.getFirst().startsWith("/")) {' 'if (false) {'
echo "all mutants killed"
