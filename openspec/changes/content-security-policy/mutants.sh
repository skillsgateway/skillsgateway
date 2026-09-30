#!/usr/bin/env bash
# Manual mutation run for content-security-policy (acceptance.md, "Failure model").
# Applies each mutant alone, runs ContentSecurityPolicyTests, restores, and proves the restore with
# `git diff --exit-code`. Fails closed: a mutant that does not apply, a survivor, a run with no test
# report, or a dirty tree after restore stops the run with a nonzero exit.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
git diff --quiet || { echo "tree is dirty; commit first"; exit 2; }
trap 'git checkout -- src/main' EXIT

JAVA_TESTS='ContentSecurityPolicyTests'
CFG=src/main/java/dev/skillsgateway/server/auth/SecurityConfig.java

# mutate FILE OLD NEW: replace exactly one occurrence, or fail.
mutate() {
  python3 - "$1" "$2" "$3" <<'PY'
import sys
path, old, new = sys.argv[1:]
text = open(path).read()
if text.count(old) != 1:
    sys.exit(f"mutant does not apply exactly once in {path}: {old!r}")
open(path, "w").write(text.replace(old, new))
PY
}

# A kill counts only when the named test fails; a run with no report (a compile error) is INVALID.
killed() { # id expected-test
  local report=target/surefire-reports/dev.skillsgateway.server.$JAVA_TESTS.txt
  rm -f "$report"
  if ./mvnw -q -Dskip.ui.verify=true -Dskip.installnodepnpm -Dskip.pnpm -Dtest="$JAVA_TESTS" \
      -Dsurefire.failIfNoSpecifiedTests=false test > "target/mutant-$1.log" 2>&1; then
    return 1
  fi
  [ -f "$report" ] || { echo "$1: INVALID (no test report; see target/mutant-$1.log)"; exit 4; }
  grep -qE "$2 .*<<< (FAILURE|ERROR)!" "$report" \
    || { echo "$1: INVALID ($2 did not fail; see $report)"; exit 4; }
}

run() { # id expected-test old new
  mutate "$CFG" "$3" "$4"
  if killed "$1" "$2"; then result=killed; else result=SURVIVED; fi
  git checkout -- "$CFG"
  git diff --exit-code > /dev/null || { echo "$1: restore left the tree dirty"; exit 3; }
  echo "$1 $result"
  [ "$result" = killed ] || exit 1
}

run M1-portal-allows-inline-script a_portal_route_carries_the_strict_policy_and_keeps_the_other_default_headers \
  "CONTENT_SECURITY_POLICY = \"default-src 'self'; script-src 'self'; \"" \
  "CONTENT_SECURITY_POLICY = \"default-src 'self'; script-src 'self' 'unsafe-inline'; \""
run M2-portal-policy-dropped a_session_api_route_carries_the_strict_policy \
  'new NegatedRequestMatcher(apiReference),' 'request -> false,'
run M3-reference-path-hardcoded the_api_reference_alone_allows_inline_script \
  'matcher(apiReferencePath + "/**")' 'matcher("/scalar/**")'
run M4-reference-gets-strict-policy the_api_reference_alone_allows_inline_script \
  "+ \"script-src 'self' 'unsafe-inline'; style-src" "+ \"script-src 'self'; style-src"
run M5-no-policy-on-login-redirect the_anonymous_login_redirect_carries_the_strict_policy \
  '        http.headers(headers -> contentSecurityPolicy(headers, apiReferencePath));
' ''
echo "all mutants killed"
