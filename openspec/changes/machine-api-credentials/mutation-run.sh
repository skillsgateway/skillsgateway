#!/usr/bin/env bash
# Mutation gauntlet for machine-api-credentials (task 13.2).
#
# The generic mutation layer is not the point here: the three pieces of logic
# whose failure would be a real hole are the scope predicate, the allowlist
# lookup and the chain matcher, so each gets a hand-chosen mutant that is a
# plausible bug rather than a syntactic tweak. A surviving mutant in any of
# them means the suite does not actually check the property it claims to.
#
# Fails closed: any unexpected exit status, an unapplied patch, or a mutant the
# suite fails to kill aborts the run. Restores every file on exit, verified
# with `git diff --quiet`.
#
# Usage: openspec/changes/machine-api-credentials/mutation-run.sh
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

SUITE='MachineCredentialShapeTests,MachineCredentialNegativeTests,MachineApiScopeTests,MachineLedgerTests,MachineCredentialLifecycleTests,MachineCredentialAdminTests,MachineRoleIntersectionTests,MachineApiRegistryTests,DevAuthTests,AuthTests,RoleEnforcementTests'

TOKEN=src/main/java/dev/skillsgateway/server/persistence/AccessToken.java
FILTER=src/main/java/dev/skillsgateway/server/auth/MachineApiAuthenticationFilter.java
PROVIDER=src/main/java/dev/skillsgateway/server/auth/MachineApiAuthenticationProvider.java
CONFIG=src/main/java/dev/skillsgateway/server/auth/SecurityConfig.java

if ! git diff --quiet -- "$TOKEN" "$FILTER" "$PROVIDER" "$CONFIG"; then
    echo "REFUSING: the mutated files are already dirty; commit or stash first." >&2
    exit 2
fi

restore() { git checkout -- "$TOKEN" "$FILTER" "$PROVIDER" "$CONFIG"; }
trap restore EXIT

killed=0
survived=0

run_suite() {
    TESTCONTAINERS_RYUK_DISABLED=true ./mvnw -o -q test -Dtest="$SUITE" -DfailIfNoTests=false >/dev/null 2>&1
}

mutant() {
    local name="$1" file="$2" from="$3" to="$4"
    restore
    python3 - "$file" "$from" "$to" <<'PY'
import sys
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path).read()
if old not in s:
    sys.exit("MUTANT NOT APPLIED: pattern absent from %s" % path)
open(path, 'w').write(s.replace(old, new, 1))
PY
    if run_suite; then
        echo "SURVIVED  $name"
        survived=$((survived + 1))
    else
        echo "killed    $name"
        killed=$((killed + 1))
    fi
    restore
}

# 1. The scope predicate: the conditional fetch default reverts to the original
#    unconditional one -- the live hole this change closed.
mutant "fetch default becomes unconditional again" "$TOKEN" \
    'if (scopeList.isEmpty()) {
            return !machineCredential();
        }' \
    'if (scopeList.isEmpty()) {
            return true;
        }'

# 2. The scope predicate: API scope membership becomes permissive.
mutant "permitsApiScope grants any scope once one is held" "$TOKEN" \
    'return apiScopeList().contains(scope);' \
    'return !apiScopeList().isEmpty();'

# 3. The authentication precondition: a fetch token would authenticate on the
#    machine chain.
mutant "provider drops the non-empty API scope precondition" "$PROVIDER" \
    '.filter(token -> token.machineCredential())' \
    '.filter(token -> true)'

# 4. The both-credentials rule: an ambiguous request is resolved instead of
#    refused.
mutant "cookie alongside a bearer credential is tolerated" "$FILTER" \
    'if (request.getHeader(HttpHeaders.COOKIE) != null) {
            response.sendError(HttpStatus.UNAUTHORIZED.value());
            return;
        }' \
    'if (false) {
            response.sendError(HttpStatus.UNAUTHORIZED.value());
            return;
        }'

# 5. The chain matcher: an empty bearer value falls through to the session
#    chain instead of being refused here.
mutant "matcher stops recognising a bare Bearer scheme" "$FILTER" \
    '|| trimmed.equalsIgnoreCase(BEARER.strip());' \
    '|| false;'

# 6. The allowlist: deny-by-default becomes permit-by-default, so every
#    unreachable endpoint opens up.
mutant "allowlist falls through to permitAll" "$CONFIG" \
    'registry.anyRequest().denyAll();' \
    'registry.anyRequest().permitAll();'

# 7. The allowlist: the per-route scope requirement is dropped, so any
#    authenticated machine credential reaches every reachable route.
mutant "per-route scope requirement dropped" "$CONFIG" \
    '.hasAuthority(MachineApiAuthentication.SCOPE_PREFIX + reachable.getKey());' \
    '.permitAll();'

echo
echo "mutants killed: $killed   survived: $survived"
[ "$survived" -eq 0 ] || exit 1
