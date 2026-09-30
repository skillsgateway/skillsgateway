#!/usr/bin/env bash
# Manual mutation run for require-oidc-issuer (evidence.md, "Mutation").
# Applies each mutant alone, runs the test that must kill it, restores, and proves the restore
# with `git diff --exit-code`. Fails closed: a mutant that does not apply, a survivor, a run with
# no test report, or a dirty tree after restore stops the run with a nonzero exit.
#
#   mutants.sh              run every mutant
#   ONLY='M1|M2' mutants.sh run a subset (the whole set can exceed one tool-call timeout)
#   DRY=1 mutants.sh        prove every mutant applies exactly once, running no test
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
git diff --quiet || { echo "tree is dirty; commit first"; exit 2; }
trap 'git checkout -- src/main helm' EXIT

C=src/main/java/dev/skillsgateway/server/auth/IdTokenDecoderConfiguration.java
H=helm/skills-gateway/templates/deployment.yaml
known='^M[1-8]-'

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
  git diff --exit-code > /dev/null || { echo "$id: restore left the tree dirty"; exit 3; }
  echo "$id $result"
  ran=$((ran + 1))
  [ "$result" = killed ] || exit 1
}

T=OidcIssuerRequiredTests
REFUSES=a_configured_provider_without_an_expected_issuer_refuses_to_start

run M1-no-refusal $T $REFUSES "$C" \
  'throw new IllegalStateException(refusal(signals));' ';'
run M2-whitespace-issuer-counts-as-set $T $REFUSES "$C" \
  '(issuer == null || issuer.isBlank())' '(issuer == null || issuer.isEmpty())'
run M3-hatch-does-not-defer $T under_the_escape_hatch_a_configured_provider_is_refused_by_the_hatch_guard "$C" \
  ' && !properties.devInsecureAuth()' ''
run M4-registrations-ignored $T $REFUSES "$C" \
  'registrations.getIfAvailable()' 'null'
run M5-issuer-not-wired-to-login $T a_configured_provider_with_an_expected_issuer_starts_and_its_login_compares_it "$C" \
  'OidcIdTokenValidation.validator(registration, issuer)' 'OidcIdTokenValidation.validator(registration, null)'
run M6-refuses-the-unconfigured-state $T the_unconfigured_state_and_the_escape_hatch_start_without_an_issuer "$C" \
  'if (!signals.isEmpty()) {' 'if (true) {'
run M7-hatch-condition-inverted $T $REFUSES "$C" \
  '&& !properties.devInsecureAuth()' '&& properties.devInsecureAuth()'
run M8-chart-issuer-optional PackagingTests chartRequiresTheExpectedIssuer "$H" \
  $'{{ required "oidc.issuer is required: the identity token\'s issuer is the tenant boundary" .Values.oidc.issuer | quote }}' \
  '{{ .Values.oidc.issuer | quote }}'

[ "$ran" -gt 0 ] || { echo "no mutant selected"; exit 5; }
if [[ -n "${DRY:-}" ]]; then echo "dry run: $ran mutants apply"; else echo "$ran mutants run, all killed"; fi
