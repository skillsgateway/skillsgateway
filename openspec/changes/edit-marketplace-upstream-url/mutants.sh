#!/usr/bin/env bash
# Manual mutation run for edit-marketplace-upstream-url (evidence.md, "Mutation").
# Applies each mutant alone, runs the test that must kill it, restores, and proves the restore
# with `git diff --exit-code`. Fails closed: a mutant that does not apply, a survivor, a run with
# no test report, or a dirty tree after restore stops the run with a nonzero exit.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
git diff --quiet || { echo "tree is dirty; commit first"; exit 2; }
trap 'git checkout -- src/main' EXIT

A=src/main/java/dev/skillsgateway/server/admin
P=src/main/java/dev/skillsgateway/server/persistence
E=src/main/java/dev/skillsgateway/server/estate
F=src/main/frontend
LOGS="${TMPDIR:-/tmp}"

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
      -Dtest="$2" -Dsurefire.failIfNoSpecifiedTests=false test > "$LOGS/mutant-$1.log" 2>&1; then
    return 1
  fi
  [ -f "$report" ] || { echo "$1: INVALID (no test report; see $LOGS/mutant-$1.log)"; exit 4; }
  python3 - "$report" "$3" <<'PY' || { echo "$1: INVALID ($3 did not fail)"; exit 4; }
import sys, xml.etree.ElementTree as ET
report, method = sys.argv[1:]
for case in ET.parse(report).getroot().iter("testcase"):
    if case.get("name", "").startswith(method) and (case.find("failure") is not None or case.find("error") is not None):
        sys.exit(0)
sys.exit(1)
PY
}

# The same rule for the portal: the named vitest test must fail in the JSON report.
ui_killed() { # id test-file expected-test
  local report="$LOGS/mutant-$1.json"
  rm -f "$report"
  if (cd "$F" && npx vitest run --project unit "$2" --reporter=json --outputFile="$report" \
      > "$LOGS/mutant-$1.log" 2>&1); then
    return 1
  fi
  [ -f "$report" ] || { echo "$1: INVALID (no test report; see $LOGS/mutant-$1.log)"; exit 4; }
  python3 - "$report" "$3" <<'PY' || { echo "$1: INVALID ($3 did not fail)"; exit 4; }
import json, sys
report, name = sys.argv[1:]
for result in json.load(open(report))["testResults"]:
    for case in result["assertionResults"]:
        if case["title"] == name and case["status"] == "failed":
            sys.exit(0)
sys.exit(1)
PY
}

run() { # id kind test expected file old new
  local id=$1 kind=$2 test=$3 expected=$4 file=$5
  # ONLY=<regex> runs a subset (the whole set exceeds one tool-call timeout); unset runs all.
  if [[ -n "${ONLY:-}" && ! $id =~ $ONLY ]]; then return 0; fi
  mutate "$file" "$6" "$7"
  # DRY=1 proves every mutant applies exactly once, without running a test.
  if [[ -n "${DRY:-}" ]]; then git checkout -- "$file"; echo "$id applies"; return 0; fi
  if "${kind}_killed" "$id" "$test" "$expected"; then result=killed; else result=SURVIVED; fi
  git checkout -- "$file"
  git diff --exit-code > /dev/null || { echo "$id: restore left the tree dirty"; exit 3; }
  echo "$id $result"
  [ "$result" = killed ] || exit 1
}

U=MarketplaceUrlChangeTests
R=MarketplaceUrlChangeRaceTests
S="$A/MarketplaceRegistrationService.java"
M="$P/MarketplaceRepository.java"
N="$P/SnapshotRepository.java"

# F1 — a new URL skips a registration check
run J1-scheme-unchecked java $U a_url_registration_would_refuse_is_refused_and_changes_nothing "$S" \
  $'        requireAllowlistedScheme(url);\n        requireNoUserinfo(url);\n        if (url.equals' \
  $'        requireNoUserinfo(url);\n        if (url.equals'
run J2-credential-unchecked java $U a_url_registration_would_refuse_is_refused_and_changes_nothing "$S" \
  $'        requireNoUserinfo(url);\n        if (url.equals' $'        if (url.equals'
run J3-unreadable-accepted java $U a_url_registration_would_refuse_is_refused_and_changes_nothing \
  "$A/AdminController.java" $'authentication.getName(),\n                MarketplaceRegistrationService.Reachability.REFUSE);' \
  $'authentication.getName(),\n                MarketplaceRegistrationService.Reachability.REPORT);'
run J4-self-warned java $U an_administrator_corrects_the_url_and_becomes_its_registrant "$S" \
  '.filter(m -> m.url() != null && !m.name().equals(self))' '.filter(m -> m.url() != null)'

# F2 — an edit after the first snapshot goes through
run J5-precheck-dropped java $U a_marketplace_with_a_snapshot_keeps_its_url_and_the_new_upstream_is_never_contacted "$S" \
  $'if (marketplaceRepository.hasSnapshot(current.id())) {' $'if (false) {'
run J6-lock-check-dropped java $R a_change_waiting_on_a_snapshot_being_recorded_is_refused "$M" \
  $'        if (hasSnapshot(id)) {\n            return UrlChange.HAS_SNAPSHOT;' \
  $'        if (false) {\n            return UrlChange.HAS_SNAPSHOT;'
run J7-row-not-locked java $R a_change_waiting_on_a_snapshot_being_recorded_is_refused "$M" \
  'WHERE id = :id FOR UPDATE")' 'WHERE id = :id")'

# F3 — content fetched from the old URL is recorded under the new one
run J8-url-not-compared java $R an_ingest_that_fetched_before_the_change_records_nothing "$N" \
  $'        if (!current) {\n            throw new MarketplaceUrlChangedException' \
  $'        if (false) {\n            throw new MarketplaceUrlChangedException'
run J9-share-lock-dropped java $R a_snapshot_waiting_on_a_change_being_made_is_not_recorded "$N" \
  '+ " WHERE id = :marketplaceId FOR SHARE")' '+ " WHERE id = :marketplaceId")'

# F5 — the previous registrant stays the registrant
run J10-registrant-kept java $U the_editor_and_not_the_original_registrant_is_kept_from_approving "$M" \
  'SET url = :url, registered_by = :actor, forge' 'SET url = :url, forge'
run J11-metadata-kept java $U an_administrator_corrects_the_url_and_becomes_its_registrant "$M" \
  $'                        + " forge_project = :forgeProject, description = :description,"\n' \
  $'                        + " forge_project = forge_project, description = description,"\n'

# F6 — the estate converges a URL the API would refuse, or refuses one it would converge
run J12-estate-refuses-unreadable java EstateReconciliationTests declared_marketplaces_face_the_same_registration_gate_as_the_api \
  "$E/EstateReconciler.java" 'ACTOR, MarketplaceRegistrationService.Reachability.REPORT)' \
  'ACTOR, MarketplaceRegistrationService.Reachability.REFUSE)'
run J13-estate-ignores-drift java EstateReconciliationTests declared_marketplaces_face_the_same_registration_gate_as_the_api \
  "$E/EstateReconciler.java" 'if (!Objects.equals(stored.url(), declared.url())) {' 'if (false) {'

# Portal (GW_INGEST_0067)
T=src/pages/marketplace-detail.test.tsx
C="$F/src/components/edit-marketplace-url.tsx"
run U1-shown-to-everyone ui $T a_user_who_is_not_an_administrator_is_not_offered_the_url_correction \
  "$F/src/pages/marketplace-detail.tsx" '{isAdmin && marketplace.origin !== "hosted"' '{marketplace.origin !== "hosted"'
run U2-offered-after-a-snapshot ui $T a_marketplace_with_a_snapshot_offers_no_url_correction_and_says_why "$C" \
  'if (snapshotCount > 0) {' 'if (false) {'
run U3-credential-accepted ui $T an_admin_corrects_the_url_before_the_first_snapshot "$C" \
  'const valid = trimmed !== "" && !credential;' 'const valid = trimmed !== "";'
run U4-declared-editable ui $T a_declared_marketplace_url_is_corrected_in_its_declaration "$C" \
  'disabled={declared || report.isLoading}' 'disabled={report.isLoading}'
run U5-untrimmed ui $T an_admin_corrects_the_url_before_the_first_snapshot "$C" \
  '{ name, url: trimmed }' '{ name, url }'
run U6-refusal-hidden ui $T an_admin_corrects_the_url_before_the_first_snapshot "$C" \
  '{change.isError ? (' '{false ? ('
run U7-stale-after-refusal ui $T a_snapshot_arriving_mid_correction_withdraws_the_control "$F/src/api/queries.ts" \
  $'        body: JSON.stringify({ url }),\n      }),\n    onSettled:' $'        body: JSON.stringify({ url }),\n      }),\n    onSuccess:'

if [[ -n "${DRY:-}" ]]; then echo "all mutants apply"; else echo "all mutants killed"; fi
