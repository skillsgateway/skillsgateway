#!/usr/bin/env bash
# Manual mutation run for vetting-runtime-dependencies-and-lsp (evidence.md, "Mutation").
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

T=vetting.ExecutableSurfaceVetterTests
P=ingestion.PluginComponentsTests
D="$V/DependencyManifests.java"
E="$V/ExecutableSurfaceVetter.java"

run M1-dev-dependencies-ignored java $T aManifestDeclaringDependenciesWarnsAtItsDeclarationAndSaysWhetherALockfileSitsBesideIt "$D" \
  '"dependencies", "devDependencies", "optionalDependencies"' '"dependencies", "optionalDependencies"'
run M2-lockfile-never-found java $T aManifestDeclaringDependenciesWarnsAtItsDeclarationAndSaysWhetherALockfileSitsBesideIt "$D" \
  $'                return candidate;' $'                return null;'
run M3-vendored-not-excluded java $T emptyVendoredOutsideAndUnparseableManifestsStaySilent "$E" \
  $'            if (VENDORED.contains(segment)) {\n                return true;' $'            if (VENDORED.contains(segment)) {\n                return false;'
run M4-empty-array-declares java $T emptyVendoredOutsideAndUnparseableManifestsStaySilent "$D" \
  $'        if (!inline.isEmpty()) {\n            return !inline.startsWith("]");' $'        if (!inline.isEmpty()) {\n            return true;'
run M5-python-counts-as-dependency java $T emptyVendoredOutsideAndUnparseableManifestsStaySilent "$D" \
  '&& !unquote(key.group(1)).equals("python")' ''
run M6-prose-scanned-as-code java $T documentationAndUnlaunchedScriptsMentioningAFetchStaySilent "$E" \
  'for (MarkdownFences.Block block : MarkdownFences.of(text)) {' 'for (MarkdownFences.Block block : java.util.List.of(new MarkdownFences.Block(1, text))) {'
run M7-launched-file-scanned-twice java $T anInstallInASkillScriptOrAFencedBlockOfASkillCommandOrAgentWarnsAtItsLine "$E" \
  '} else if (!launched.contains(path)' '} else if (true'
run M8-skill-fetch-medium java $T downloadAndExecuteInASkillScriptOrAFencedBlockBlocksAtItsLine "$E" \
  $'                    runner ? RUNTIME_DEPENDENCY : SKILL_FETCH_EXEC,\n                    runner ? Severity.MEDIUM : Severity.HIGH,' $'                    runner ? RUNTIME_DEPENDENCY : SKILL_FETCH_EXEC,\n                    Severity.MEDIUM,'
run M9-lsp-carries-mcp-ids java $T anLspServerThatRunsAPackageRunnerWarnsAtItsCommand "$E" \
  'LSP("LSP server", LSP_FETCH_EXEC, LSP_PACKAGE_RUN);' 'LSP("LSP server", MCP_FETCH_EXEC, MCP_PACKAGE_RUN);'
run M10-plugin-json-lsp-unread java $P lspServersAreReadFromEverySourceAndALaterNameReplacesAnEarlierOne "$I/PluginComponents.java" \
  'reader.lspDeclaration(plugin.root().path("lspServers"), plugin, "/lspServers", lsp);' ';'
run M11-one-walk-per-file java $T skillFilesAreReadInOneWalkWhateverTheirNumber "$E" \
  'snapshot.walk(wanted::contains, contents::put);' 'for (String one : wanted) { snapshot.walk(one::equals, contents::put); }'
run M12-entry-without-command-read java $P lspServersAreReadFromEverySourceAndALaterNameReplacesAnEarlierOne "$I/PluginComponents.java" \
  $'                if (!server.path("command").isTextual()) {\n                    return;\n                }' ''
run M13-local-runner-flagged java $T anLspServerOnThePathABinaryAndALocalRunnerStaySilent "$E" \
  'if (runner && localRunner && match.line() == 1) {' 'if (false) {'
if [[ -n "${DRY:-}" ]]; then echo "dry run: every mutant applies"; else echo "all mutants killed"; fi
