#!/usr/bin/env bash
# Manual mutation run for actionable-vetting-findings (evidence.md, "Mutation").
# Applies each mutant alone, runs the tests that must kill it, restores, and proves the restore
# with `git diff --exit-code`. Fails closed: a mutant that does not apply, a survivor, a run with
# no test report, or a dirty tree after restore stops the run with a nonzero exit.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
git diff --quiet || { echo "tree is dirty; commit first"; exit 2; }
trap 'git checkout -- src/main' EXIT

V=src/main/java/dev/skillsgateway/server/vetting
WEB=src/main/frontend/src/components/vetting-report.tsx

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
  if (cd src/main/frontend && npx vitest run --project unit src/components/vetting-report.test.tsx > "/tmp/mutant-$1.log" 2>&1); then
    return 1
  fi
  grep -q "× $3" "/tmp/mutant-$1.log" || { echo "$1: INVALID ($3 did not fail)"; exit 4; }
}

run() { # id kind test-class expected file old new
  local id=$1 kind=$2 cls=$3 expected=$4 file=$5
  mutate "$file" "$6" "$7"
  if "${kind}_killed" "$id" "$cls" "$expected"; then result=killed; else result=SURVIVED; fi
  git checkout -- "$file"
  git diff --exit-code > /dev/null || { echo "$id: restore left the tree dirty"; exit 3; }
  echo "$id $result"
  [ "$result" = killed ] || exit 1
}

run M1-content-ignored java vetting.FindingGroupWaiverTests aGroupWaiverNeverCoversContentThatDiffers "$V/Waiver.java" \
  '                && coversContent(finding);' '                && true;'
run M2-line-ignored java vetting.FindingGroupWaiverTests aGroupWaiverNeverCoversContentThatDiffers "$V/Waiver.java" \
  '(content.equals(finding.content()) && Objects.equals(line, finding.line()))' '(content.equals(finding.content()))'
run M3-vetter-claim-trusted java vetting.QuarantineSnapshotTests findingsAreIdentifiedByTheBlobThePinnedTreeHoldsAtTheirPath "$V/QuarantineSnapshot.java" \
  'return finding.withContent(blob == null ? null : blob.name());' \
  'return finding.content() != null ? finding : finding.withContent(blob == null ? null : blob.name());'
run M4-path-scoped-group java vetting.FindingGroupWaiverTests aGroupWaiverThatIsNotSnapshotScopedOrNamesNoBlobCannotBeBuilt "$V/Waiver.java" \
  'if (content != null && scope != WaiverScope.SNAPSHOT) {' 'if (false) {'
run M5-blobless-collapse java vetting.FindingGroupWaiverTests findingsDifferingInBlobLineRuleOrIdentityNeverCollapse "$V/FindingGroup.java" \
  '? new Object() // identity: never equal to another finding' '? (Object) "no-blob" // identity: never equal to another finding'
run M6-clause-unbounded java vetting.PromptInjectionPrecisionTests theLinesThatRaisedFalsePositivesRaiseNothing "$V/PromptInjectionVetter.java" \
  'return "(?:(?![.!?;:]\\s)[^\\n]){0," + max + "}";' 'return "[^\\n]{0," + max + "}";'
run M7-refusal-unbounded java vetting.FindingGroupWaiverTests theWorklistHasOneEntryPerGroupNamingEveryLocation "$V/WaiverEvaluation.java" \
  'static final int DESCRIBED_LOCATIONS = 10;' 'static final int DESCRIBED_LOCATIONS = 100;'
run M8-unscanned-per-file java vetting.UnscannedFilesTests theSecretScanReportsSkippedFilesAsOneEntryPerReason "$V/ContentRules.java" \
  'static final int NAMED_UNSCANNED = 20;' 'static final int NAMED_UNSCANNED = 30;'
run M9-service-accepts-path-group java WaiverTests aGroupWaiverCoversEveryCopyOfItsContentAndNothingElse "$V/WaiverService.java" \
  'if (content != null && scope != WaiverScope.SNAPSHOT) {' 'if (false) {'
run M10-form-defaults-wide ui - waiving_a_group_defaults_to_that_group_and_posts_its_content "$WEB" \
  'useState<WaiveChoice>(groupable ? "group" : "snapshot")' 'useState<WaiveChoice>("snapshot")'
echo "all mutants killed"
