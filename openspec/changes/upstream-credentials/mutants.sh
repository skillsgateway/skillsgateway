#!/usr/bin/env bash
# Manual mutation run for upstream-credentials (acceptance.md). Applies each mutant alone, runs the
# test class that must kill it, restores, and proves the restore with `git diff --exit-code`. Fails
# closed: a mutant that does not apply, a survivor, a run with no test report, or a dirty tree after
# restore stops the run with a nonzero exit.
#
# M1 is not a typo-sized bug but the rejected design: JGit's own CredentialsProvider in place of the
# gateway's per-request connection factory (design.md, decision 1). The redirect tests must fail
# against it, or they are not testing what they claim.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
git diff --quiet || { echo "tree is dirty; commit first"; exit 2; }
trap 'git checkout -- src/main' EXIT
LOGS="${TMPDIR:-/tmp}/upstream-credentials-mutants"
mkdir -p "$LOGS"

ING=src/main/java/dev/skillsgateway/server/ingestion
CRED=$ING/UpstreamCredentials.java
GIT=$ING/UpstreamGit.java
TRN=$ING/UpstreamFailure.java
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

run() { # id Class.test file old new [file old new]...
  local id=$1 expected=$2
  shift 2
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

IT=UpstreamCredentialsIntegrationTests
UT=UpstreamCredentialsTests

JGIT_CONNECT='                .ifPresent(selected -> command.setTransportConfigCallback(transport -> {
                    if (transport instanceof TransportHttp http) {
                        http.setHttpConnectionFactory(selected.connectionFactory());
                    }
                }));'
JGIT_PROVIDER='        public org.eclipse.jgit.transport.CredentialsProvider jgit() {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(58);
            return new org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(
                    decoded.substring(0, colon), decoded.substring(colon + 1));
        }

        public String urlPrefix() {'

for victim in a_redirect_to_another_port_of_the_same_host_carries_no_credential \
    a_redirect_to_another_path_of_the_same_host_carries_no_credential \
    a_redirect_into_another_credentialed_prefix_does_not_switch_tokens; do
  run "M1-jgit-credentials-provider-$victim" "$IT.$victim" \
    "$GIT" "$JGIT_CONNECT" '                .ifPresent(selected -> command.setCredentialsProvider(selected.jgit()));' \
    "$CRED" '        public String urlPrefix() {' "$JGIT_PROVIDER"
done
run M2-every-request-covered "$IT.a_redirect_to_another_port_of_the_same_host_carries_no_credential" \
  "$CRED" '                if (covers(url)) {' '                if (url != null) {'
run M3-no-segment-boundary "$UT.a_prefix_matches_whole_path_segments_only" \
  "$CRED" 'other.path.startsWith(path + "/")' 'other.path.startsWith(path)'
run M4-first-prefix-wins "$UT.the_longest_prefix_wins_whatever_the_order" \
  "$CRED" '.max(Comparator.comparingInt(entry -> entry.prefix.path().length()));' '.findFirst();'
run M5-dot-segments-allowed "$UT.a_non_canonical_request_url_is_never_covered" \
  "$CRED" '                    || !uri.normalize().getRawPath().equals(rawPath)) {' '                    ) {'
run M6-encoded-dots-allowed "$UT.a_non_canonical_request_url_is_never_covered" \
  "$CRED" '                    || lowered.contains("%2e")' ''
run M7-jgit-may-set-authorization "$UT.the_connection_factory_attaches_the_credential_only_under_the_prefix" \
  "$CRED" 'if (!"authorization".equalsIgnoreCase(key)) {' 'if (key != null) {'
run M8-jdk-follows-redirects "$UT.the_jdk_is_never_allowed_to_follow_a_redirect_with_the_credential" \
  "$CRED" '            delegate.setInstanceFollowRedirects(false);' '            delegate.setInstanceFollowRedirects(followRedirects);'
run M9-placeholder-accepted "$UT.an_unusable_entry_is_refused_naming_the_entry_and_never_the_token" \
  "$CRED" '        if (value.contains("${")) {' '        if (value.isEmpty()) {'
run M10-cleartext-anywhere "$UT.an_unusable_entry_is_refused_naming_the_entry_and_never_the_token" \
  "$CRED" '!(cleartext && isLoopback(prefix.host()))' '!cleartext'
run M11-duplicates-accepted "$UT.two_entries_for_one_prefix_are_refused" \
  "$CRED" 'if (earlier.prefix.equals(entry.prefix)) {' 'if (earlier == entry) {'
run M12-port-ignored "$UT.a_non_canonical_request_url_is_never_covered" \
  "$CRED" '                    && port == other.port
                    && (other.path' '                    && (other.path'
run M13-credential-never-installed "$IT.a_private_upstream_registers_and_ingests_with_the_longest_prefix_credential" \
  "$GIT" '                        http.setHttpConnectionFactory(selected.connectionFactory());' ''
run M14-failure-not-scrubbed "$IT.the_token_is_never_repeated" \
  "$GIT" 'return UpstreamFailure.of(failure).scrub(credentials.secrets());' 'return UpstreamFailure.of(failure);'
run M15-scrub-does-nothing "$UT.a_token_a_server_quotes_is_scrubbed_from_the_failure" \
  "$TRN" '                out = out.replace(secret, "***");' ''
run M16-userinfo-accepted "$IT.a_clone_url_with_userinfo_is_refused_before_the_upstream_is_contacted" \
  "$REG" '            requireNoUserinfo(url);
' ''
run M17-ledger-silent "$IT.the_registration_ledger_entry_names_the_prefix_and_never_the_token" \
  "$REG" '.map(selected -> detail + " credential=" + selected.urlPrefix())' '.map(selected -> detail)'
run M18-tostring-prints-token "$UT.the_properties_never_print_the_token" \
  "$PROPS" '"UpstreamCredential[urlPrefix=%s, username=%s]".formatted(urlPrefix, username)' \
  '"UpstreamCredential[urlPrefix=%s, username=%s, token=%s]".formatted(urlPrefix, username, token)'
echo "all mutants killed"
