#!/usr/bin/env bash
# Manual mutation run for role-grant-principal-validation (evidence.md, "Mutation").
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

P=src/main/java/dev/skillsgateway/server/roles/RolePrincipals.java
S=src/main/java/dev/skillsgateway/server/roles/RoleService.java
E=src/main/java/dev/skillsgateway/server/estate/EstateReconciler.java
C=src/main/java/dev/skillsgateway/server/roles/ClaimRoleMapper.java
known='^M([1-9]|1[0-2])-'

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


U=RolePrincipalsTests
run M1-api-unchecked RoleEnforcementTests a_malformed_principal_is_refused_unrecorded_and_a_well_formed_one_is_granted_exactly "$S" \
  'RolePrincipals.requireGrantable(principal);' 'if (principal == null || principal.isBlank()) throw new IllegalArgumentException();'
run M2-estate-checks-after-existence EstateReconciliationTests a_declared_grant_with_a_malformed_principal_fails_in_isolation_even_over_a_stored_row "$E" \
  'RolePrincipals.requireGrantable(declared.principal());' ''
run M3-list-values-trimmed ClaimRoleMapperTests a_padded_or_altered_claim_value_confers_no_role_and_a_lone_exact_string_does "$C" \
  $'\n                    values.add(text);' $'\n                    values.add(text.trim());'
run M4-lone-value-trimmed ClaimRoleMapperTests a_padded_or_altered_claim_value_confers_no_role_and_a_lone_exact_string_does "$C" \
  $'\n            values.add(text);' $'\n            values.add(text.trim());'
run M5-configured-value-unchecked ClaimRoleMapperTests a_padded_or_invisible_configured_claim_value_refuses_construction "$C" \
  'if (malformed.isPresent()) {' 'if (false) {'
run M6-no-break-space-allowed $U well_formed_values_pass_and_malformed_ones_name_their_fault "$P" \
  'return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);' 'return Character.isWhitespace(codePoint);'
run M7-trailing-space-allowed $U well_formed_values_pass_and_malformed_ones_name_their_fault "$P" \
  'if (isSpace(value.codePointAt(0)) || isSpace(value.codePointBefore(value.length()))) {' 'if (isSpace(value.codePointAt(0))) {'
run M8-format-allowed $U well_formed_values_pass_and_malformed_ones_name_their_fault "$P" \
  $'Character.FORMAT,\n' ''
run M9-lone-surrogate-allowed $U well_formed_values_pass_and_malformed_ones_name_their_fault "$P" \
  $'Character.PARAGRAPH_SEPARATOR,\n                    Character.SURROGATE -> true;' 'Character.PARAGRAPH_SEPARATOR -> true;'
run M10-length-in-utf16-units $U a_grant_refusal_is_422_and_never_repeats_the_value "$P" \
  'principal.codePointCount(0, principal.length()) > MAX_LENGTH' 'principal.length() > MAX_LENGTH'
run M11-refusal-echoes-value $U a_grant_refusal_is_422_and_never_repeats_the_value "$P" \
  '"the principal " + problem.get()' '"the principal " + principal + " " + problem.get()'
run M12-display-unescaped EstateReconciliationTests a_declared_grant_with_a_malformed_principal_fails_in_isolation_even_over_a_stored_row "$P" \
  'if (isInvisible(codePoint)) {' 'if (false) {'

[ "$ran" -gt 0 ] || { echo "no mutant selected"; exit 5; }
if [[ -n "${DRY:-}" ]]; then echo "$ran mutants apply"; else echo "$ran mutants run, all killed"; fi
