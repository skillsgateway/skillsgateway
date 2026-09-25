#!/usr/bin/env bash
# Manual mutation run for ingest-failures-say-why (acceptance.md, "Gauntlet plan").
# Applies each mutant alone, runs the test class that must kill it, restores, and proves the restore
# with `git diff --exit-code`. Fails closed: a mutant that does not apply, a survivor, a run with no
# test report, or a dirty tree after restore stops the run with a nonzero exit.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
git diff --quiet || { echo "tree is dirty; commit first"; exit 2; }
trap 'git checkout -- src/main' EXIT
LOGS="${TMPDIR:-/tmp}/ingest-failures-mutants"
mkdir -p "$LOGS"

ING=src/main/java/dev/skillsgateway/server/ingestion
REG=src/main/java/dev/skillsgateway/server/admin/MarketplaceRegistrationService.java
SVC=$ING/IngestionService.java
GIT=$ING/UpstreamGit.java
TRN=$ING/UpstreamFailure.java
WEB=src/main/frontend/src/pages/marketplace-detail.tsx

# mutate FILE OLD NEW: replace exactly one occurrence, or fail.
mutate() {
  python3 - "$1" "$2" "$3" <<'EOF'
import sys
path, old, new = sys.argv[1:]
text = open(path).read()
if text.count(old) != 1:
    sys.exit(f"mutant does not apply exactly once in {path}: {old!r}")
open(path, "w").write(text.replace(old, new))
EOF
}

# A kill counts only when the named test fails; a run with no report (a compile error, a database
# that never started) is INVALID, never a kill.
java_killed() { # id Class.test
  local class=${2%%.*} test=${2#*.} report
  report=$(find target/surefire-reports -name "*.$class.txt" 2>/dev/null | head -1)
  [ -n "$report" ] && rm -f "$report"
  if ./mvnw -q -Dskip.ui.verify=true -Dskip.installnodenpm -Dskip.pnpm -Dcheckstyle.skip=true \
      -Dspotless.check.skip=true -Dtest="$class" -Dsurefire.failIfNoSpecifiedTests=false test \
      > "$LOGS/$1.log" 2>&1; then
    return 1
  fi
  report=$(find target/surefire-reports -name "*.$class.txt" 2>/dev/null | head -1)
  [ -n "$report" ] || { echo "$1: INVALID (no test report; see $LOGS/$1.log)"; exit 4; }
  grep -qE "$test.*<<< (FAILURE|ERROR)!" "$report" \
    || { echo "$1: INVALID ($test did not fail; see $report)"; exit 4; }
}

ui_killed() { # id test-name
  if (cd src/main/frontend && npx vitest run --project unit src/pages/marketplace-detail.test.tsx \
      > "$LOGS/$1.log" 2>&1); then
    return 1
  fi
  grep -q "× $2" "$LOGS/$1.log" || { echo "$1: INVALID ($2 did not fail)"; exit 4; }
}

run() { # id kind expected file old new
  local id=$1 kind=$2 expected=$3 file=$4
  mutate "$file" "$5" "$6"
  if "${kind}_killed" "$id" "$expected"; then result=killed; else result=SURVIVED; fi
  git checkout -- "$file"
  git diff --exit-code > /dev/null || { echo "$id: restore left the tree dirty"; exit 3; }
  echo "$id $result"
  [ "$result" = killed ] || exit 1
}

run M1-probe-removed java UpstreamReachabilityTests.a_repository_that_does_not_exist_is_refused_and_nothing_is_created "$REG" \
  '            upstreamGit.probe(url);
' ''
run M2-not-found-branch-removed java UpstreamFailureTests.not_found_and_every_authentication_refusal_read_the_same "$TRN" \
  'if (candidate instanceof NoRemoteRepositoryException || refusalText(candidate)) {' 'if (false) {'
run M3-probe-always-refuses java UpstreamReachabilityTests.a_readable_upstream_is_registered_over_http_and_file "$GIT" \
  '        Map<String, Ref> refs;
' '        if (url != null) {
            throw new UpstreamException(UpstreamFailure.noDefaultBranch("mutant"), null);
        }
        Map<String, Ref> refs;
'
run M4-failure-not-recorded java IngestFailureTests.a_failed_ingest_says_why_and_is_recorded_and_a_later_success_clears_it "$SVC" \
  '        marketplaceRepository.recordIngest(marketplace.id(), Marketplace.INGEST_FAILED, reason);
' ''
run M5-success-not-recorded java IngestFailureTests.a_failed_ingest_says_why_and_is_recorded_and_a_later_success_clears_it "$SVC" \
  '            marketplaceRepository.recordIngest(marketplace.id(), Marketplace.INGEST_SUCCEEDED, null);
' ''
run M6-first-error-dropped java IngestFailureTests.the_head_fetch_error_stays_attached_to_the_fallbacks "$GIT" \
  '                    second.addSuppressed(first);
                    throw new UpstreamException' '                    throw new UpstreamException'
run M7-redaction-removed java UpstreamFailureTests.a_credential_in_a_url_is_never_repeated "$TRN" \
  'return text == null ? null : USERINFO.matcher(text).replaceAll("$1***@");' 'return text;'
run M8-estate-refuses java EstateReconciliationTests.a_declared_marketplace_with_an_unreachable_upstream_is_registered_and_reported "$REG" \
  'if (reachability == Reachability.REFUSE) {' 'if (true) {'
run M9-probe-before-conflict-check java UpstreamReachabilityTests.a_request_refused_without_the_network_never_contacts_the_upstream "$REG" \
  '        if (marketplaceRepository.findByName(name).isPresent()) {' \
  '        if (url != null) {
            readUpstream(name, url, Reachability.REPORT);
        }
        if (marketplaceRepository.findByName(name).isPresent()) {'
run M10-portal-alert-removed ui a_failed_last_ingest_is_stated_with_when_and_why "$WEB" \
  '{marketplace.lastIngestOutcome === "failed" ? (' '{false ? ('
echo "all mutants killed"
