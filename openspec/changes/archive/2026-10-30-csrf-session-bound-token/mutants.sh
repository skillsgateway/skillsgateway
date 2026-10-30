#!/usr/bin/env bash
# Manual mutation run for csrf-session-bound-token (evidence.md, "Mutation").
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

SC=src/main/java/dev/skillsgateway/server/auth/SecurityConfig.java
RP=src/main/java/dev/skillsgateway/server/auth/SessionBoundCsrfTokenRepository.java
known='^M([1-9][0-9]*)-'

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

I=SessionBoundCsrfTests
U=SessionBoundCsrfTokenRepositoryTests
run M1-cookie-read-as-token $I the_portal_flow_is_accepted_and_a_pair_the_session_was_never_issued_is_refused "$RP" \
  'return session.loadToken(request);' 'var c = WebUtils.getCookie(request, COOKIE_NAME); return c != null ? new org.springframework.security.web.csrf.DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", c.getValue()) : session.loadToken(request);'
run M2-cookie-repository-back $I a_token_issued_to_one_session_is_refused_on_another "$SC" \
  'csrf.spa().csrfTokenRepository(new SessionBoundCsrfTokenRepository(sessionMayStart))' 'csrf.spa().csrfTokenRepository(org.springframework.security.web.csrf.CookieCsrfTokenRepository.withHttpOnlyFalse())'
run M3-token-creates-session $I an_unauthenticated_request_is_answered_without_creating_a_session "$RP" \
  'if (request.getSession(false) == null && !sessionMayStart.matches(request)) {' 'if (false) {'
run M4-api-request-saved $I an_unauthenticated_request_is_answered_without_creating_a_session "$SC" \
  'matcher("/api/**")),' 'matcher("/nothing/**")),'
run M5-error-dispatch-saved $I an_unauthenticated_request_is_answered_without_creating_a_session "$SC" \
  $'no page either.\n                new DispatcherTypeRequestMatcher(DispatcherType.REQUEST),' \
  $'no page either.\n                new org.springframework.security.web.util.matcher.OrRequestMatcher(new DispatcherTypeRequestMatcher(DispatcherType.REQUEST), new DispatcherTypeRequestMatcher(DispatcherType.ERROR)),'
run M6-sign-in-starts-no-session SessionCookieTests the_login_redirect_writes_a_readable_token_cookie_and_a_lax_session_cookie "$SC" \
  '+ "/{registrationId}"' '+ "/never/{registrationId}"'
run M7-no-same-site $U a_saved_token_is_stored_in_the_session_and_copied_to_a_same_site_script_readable_cookie "$RP" \
  '.sameSite("Lax")' '.sameSite(null)'
run M8-never-secure $U the_cookie_is_secure_when_the_request_is "$RP" \
  '.secure(request.isSecure())' '.secure(false)'
run M9-copy-not-repaired $U with_a_session_the_stored_token_wins_over_the_cookie_and_the_copy_is_repaired "$RP" \
  'if (copy == null || !token.getToken().equals(copy.getValue())) {' 'if (false) {'
run M10-non-page-get-saved $I an_unauthenticated_request_is_answered_without_creating_a_session "$SC" \
  $'XMLHttpRequest")),\n                pageNavigation()));' $'XMLHttpRequest"))));'
run M11-page-navigation-not-saved $I a_page_navigation_is_redirected_to_the_sign_in_and_kept_to_return_to "$SC" \
  $'XMLHttpRequest")),\n                pageNavigation()));' $'XMLHttpRequest")),\n                request -> false));'
run M12-spa-handler-dropped $I the_portal_flow_is_accepted_and_a_pair_the_session_was_never_issued_is_refused "$SC" \
  'csrf.spa().csrfTokenRepository(' 'csrf.csrfTokenRepository('

[ "$ran" -gt 0 ] || { echo "no mutant selected"; exit 5; }
if [[ -n "${DRY:-}" ]]; then echo "$ran mutants apply"; else echo "$ran mutants run, all killed"; fi
