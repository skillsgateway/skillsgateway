#!/usr/bin/env bash
# Manual mutation run for upstream-github-app (acceptance.md). Applies each mutant alone, runs the
# test class that must kill it, restores, and proves the restore with `git diff --exit-code`. Fails
# closed: a mutant that does not apply, a survivor, a run with no test report, or a dirty tree after
# restore stops the run with a nonzero exit. The runner itself is #498's, unchanged.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
git diff --quiet || { echo "tree is dirty; commit first"; exit 2; }
trap 'git checkout -- src/main' EXIT
LOGS="${TMPDIR:-/tmp}/upstream-github-app-mutants"
mkdir -p "$LOGS"

ING=src/main/java/dev/skillsgateway/server/ingestion
APP=$ING/GitHubAppTokens.java
CRED=$ING/UpstreamCredentials.java
GIT=$ING/UpstreamGit.java
REG=src/main/java/dev/skillsgateway/server/admin/MarketplaceRegistrationService.java
PROPS=src/main/java/dev/skillsgateway/server/config/SkillsGatewayProperties.java

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
killed() { # id Class.test
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

# With arguments, runs only the mutants whose ids start with one of them (e.g. `mutants.sh M1- M7-`),
# so a long run can be split; with none, runs all. A named id that matches nothing fails the run.
SELECTED=("$@")
RAN=()
run() { # id Class.test file old new [file old new]...
  local id=$1 expected=$2
  shift 2
  if [ ${#SELECTED[@]} -gt 0 ]; then
    local wanted=no
    for prefix in "${SELECTED[@]}"; do
      case "$id" in "$prefix"*) wanted=yes; RAN+=("$prefix") ;; esac
    done
    [ "$wanted" = yes ] || return 0
  fi
  while [ $# -gt 0 ]; do
    mutate "$1" "$2" "$3"
    shift 3
  done
  if killed "$id" "$expected"; then result=killed; else result=SURVIVED; fi
  git checkout -- src/main
  git diff --exit-code > /dev/null || { echo "$id: restore left the tree dirty"; exit 3; }
  echo "$id $result"
  [ "$result" = killed ] || exit 1
}

IT=UpstreamGitHubAppIntegrationTests
UT=GitHubAppTokensTests

run M1-assertion-not-backdated "$UT.the_assertion_is_signed_by_the_app_key_and_short_lived" \
  "$APP" '.issueTime(Date.from(now.minus(BACKDATE)))' '.issueTime(Date.from(now))'
run M2-assertion-too-long "$UT.the_assertion_is_signed_by_the_app_key_and_short_lived" \
  "$APP" 'ASSERTION_LIFETIME = Duration.ofMinutes(9);' 'ASSERTION_LIFETIME = Duration.ofMinutes(11);'
run M3-no-renewal-margin "$UT.a_token_is_reused_until_five_minutes_before_it_expires" \
  "$APP" 'RENEW_MARGIN = Duration.ofMinutes(5);' 'RENEW_MARGIN = Duration.ZERO;'
run M4-token-for-every-repository "$IT.a_private_upstream_registers_and_ingests_with_a_minted_installation_token" \
  "$APP" '"{\"repositories\":[\"" + repo.name() + "\"],\"permissions\"' '"{\"permissions\"'
run M5-token-can-write "$IT.a_private_upstream_registers_and_ingests_with_a_minted_installation_token" \
  "$APP" '{\"contents\":\"read\"}}";' '{\"contents\":\"write\"}}";'
run M6-api-redirect-followed "$IT.a_redirect_from_the_api_is_not_followed" \
  "$APP" 'followRedirects(HttpClient.Redirect.NEVER)' 'followRedirects(HttpClient.Redirect.ALWAYS)'
run M7-never-renewed "$IT.a_refused_cached_token_is_renewed_once_and_only_once" \
  "$GIT" 'if (!access.renewable()) {' 'if (true) {'
run M8-fresh-token-renewed-too "$IT.a_refused_cached_token_is_renewed_once_and_only_once" \
  "$APP" 'return new Minted(fresh.token(), false);' 'return new Minted(fresh.token(), true);'
run M9-refused-token-kept "$IT.a_refused_cached_token_is_renewed_once_and_only_once" \
  "$GIT" 'if (refused(again)) {' 'if (false) {'
run M10-extra-path-segments "$UT.only_a_plain_owner_and_repository_name_a_repository" \
  "$APP" 'if (segments.length != 2) {' 'if (segments.length < 2) {'
run M11-any-name-reaches-the-api "$IT.the_api_is_only_ever_the_configured_one" \
  "$APP" 'Pattern.compile("[A-Za-z0-9._-]+")' 'Pattern.compile("[^/]+")'
run M12-cleartext-api "$UT.an_unusable_app_entry_stops_startup_naming_the_entry" \
  "$APP" '|| ("http".equals(scheme) && uri.getHost() != null && UpstreamCredentials.isLoopback(uri.getHost()));' \
  '|| "http".equals(scheme);'
run M13-both-kinds-accepted "$UT.an_unusable_app_entry_stops_startup_naming_the_entry" \
  "$CRED" '} else if (credential.username() != null || credential.token() != null) {' '} else if (false) {'
run M14-pkcs1-unread "$UT.github_issues_pkcs1_and_both_forms_load" \
  "$APP" 'label.equals("RSA PRIVATE KEY") ? pkcs8(der) : der' 'der'
run M15-not-selected-misread "$IT.each_api_refusal_is_reported_with_its_own_reason" \
  "$APP" 'status == 422 && phase == Phase.TOKEN' 'status == 423 && phase == Phase.TOKEN'
run M16-server-clock-ignored "$UT.a_refusal_from_the_api_reads_as_its_cause" \
  "$APP" ' || skewed(date, now)) {' ') {'
run M17-suspension-misread "$IT.each_api_refusal_is_reported_with_its_own_reason" \
  "$APP" 'if (status == 403 && text' 'if (status == 499 && text'
run M18-tostring-prints-key "$UT.the_properties_never_print_the_key" \
  "$PROPS" '"GitHubApp[appId=%s, installationId=%s, apiUrl=%s]".formatted(appId, installationId, apiUrl)' \
  '"GitHubApp[appId=%s, installationId=%s, apiUrl=%s, privateKey=%s]".formatted(appId, installationId, apiUrl, privateKey)'
run M19-replaced-token-not-scrubbed "$IT.the_key_the_assertion_and_the_token_are_never_repeated" \
  "$APP" 'if (replaced != null) {' 'if (false) {'
run M20-key-quoted-in-error "$UT.an_unreadable_key_stops_startup_and_is_never_quoted" \
  "$APP" '"the key is not valid base64");' '"the key is not valid base64: " + body);'
run M21-ledger-names-no-kind "$IT.the_registration_ledger_entry_names_the_github_app_kind" \
  "$REG" '(selected.isGitHubApp() ? " (github-app)" : "")' '""'
run M22-token-outside-prefix "$IT.a_redirect_to_another_port_carries_no_installation_token" \
  "$CRED" '                if (covers(url)) {' '                if (url != null) {'
run M23-installation-always-looked-up "$IT.a_configured_installation_is_minted_from_without_a_lookup" \
  "$APP" 'if (installationId != null) {' 'if (false) {'
run M24-unreachable-misread "$IT.each_api_refusal_is_reported_with_its_own_reason" \
  "$APP" '                        UpstreamFailure.APP_API_UNREACHABLE,' '                        UpstreamFailure.APP_NO_TOKEN,'
run M25-token-not-scrubbed-from-git-failure "$IT.the_key_the_assertion_and_the_token_are_never_repeated" \
  "$GIT" '        return UpstreamFailure.of(failure).scrub(secrets);' '        return UpstreamFailure.of(failure);'
for prefix in "${SELECTED[@]}"; do
  printf '%s\n' "${RAN[@]}" | grep -qxF -- "$prefix" || { echo "$prefix: no such mutant"; exit 5; }
done
echo "all selected mutants killed (${SELECTED[*]:-all})"
