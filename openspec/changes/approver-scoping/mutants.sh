#!/usr/bin/env bash
# Manual mutation run for approver-scoping (evidence.md, "Mutation").
# Applies each mutant alone, runs the test that must kill it, restores, and proves the restore
# with `git diff --exit-code -- src/main`. Fails closed: a mutant that does not apply, a survivor,
# a run with no test report, or a dirty src/main after restore stops the run with a nonzero exit.
#
#   mutants.sh              run every mutant
#   ONLY='M1|M2' mutants.sh run a subset
#   DRY=1 mutants.sh        prove every mutant applies exactly once, running no test
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
git diff --quiet -- src/main || { echo "src/main is dirty; commit first"; exit 2; }
trap 'git checkout -- src/main' EXIT

RS=src/main/java/dev/skillsgateway/server/roles/RoleService.java
FL=src/main/java/dev/skillsgateway/server/persistence/FetchLogRepository.java
known='^M[1-2]-'

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
killed() { # id test-class expected-method
  local report=target/surefire-reports/TEST-dev.skillsgateway.server.$2.xml
  rm -f "$report"
  if ./mvnw -q -o -Dskip.ui.verify=true -Dskip.installnodepnpm -Dskip.pnpm -Djacoco.skip=true \
      -Dspotless.check.skip=true -Dcheckstyle.skip=true \
      -Dtest="$2" -Dsurefire.failIfNoSpecifiedTests=false test > "target/mutant-$1.log" 2>&1; then
    return 1
  fi
  [ -f "$report" ] || { echo "$1: INVALID (no test report; see target/mutant-$1.log)"; exit 4; }
  python3 - "$report" "$3" <<'PY' || { echo "$1: INVALID ($3 did not fail)"; exit 4; }
import sys, xml.etree.ElementTree as ET
report, method = sys.argv[1:]
for case in ET.parse(report).getroot().iter("testcase"):
    if case.get("name", "") == method and (case.find("failure") is not None or case.find("error") is not None):
        sys.exit(0)
sys.exit(1)
PY
}

ran=0
run() { # id test-class expected-method file old new
  local id=$1 cls=$2 expected=$3 file=$4
  [[ $id =~ $known ]] || { echo "$id: malformed id"; exit 5; }
  if [[ -n "${ONLY:-}" && ! $id =~ ^(${ONLY})- ]]; then return 0; fi
  mutate "$file" "$5" "$6"
  if [[ -n "${DRY:-}" ]]; then git checkout -- "$file"; echo "$id applies"; ran=$((ran + 1)); return 0; fi
  mkdir -p target
  if killed "$id" "$cls" "$expected"; then result=killed; else result=SURVIVED; fi
  git checkout -- "$file"
  git diff --exit-code -- src/main > /dev/null || { echo "$id: restore left src/main dirty"; exit 3; }
  echo "$id $result"
  ran=$((ran + 1))
  [ "$result" = killed ] || exit 1
}

run M1-name-not-live MarketplaceRemovalTests the_approver_of_a_reused_name_is_refused_the_predecessors_snapshot_and_an_admin_is_not "$RS" \
  '                        .filter(id -> id == marketplaceId)' '                        .filter(id -> true)'
run M2-fetchers-unbound FetchersScopeTests the_fetchers_of_a_commit_two_marketplaces_serve_are_reported_per_marketplace "$FL" \
  '" WHERE marketplace_id = :marketplaceId AND sha = :sha AND event = '"'"'upload-pack'"'"'"' '" WHERE :marketplaceId > 0 AND sha = :sha AND event = '"'"'upload-pack'"'"'"'

[ "$ran" -gt 0 ] || { echo "no mutant selected"; exit 5; }
if [[ -n "${DRY:-}" ]]; then echo "$ran mutants apply"; else echo "$ran mutants run, all killed"; fi
