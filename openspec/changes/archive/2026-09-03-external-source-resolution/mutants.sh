#!/usr/bin/env bash
#
# Manual mutation testing for external-source-resolution (old-coder gauntlet).
#
# The project has no mutation-testing plugin, so this is the hand-rolled
# procedure: introduce one plausible bug at a time, run the test that should
# catch it, and require that it fails. A mutant that survives is a hole in the
# suite and is reported as one.
#
# It fails closed, on purpose and in every direction:
#   * a search string that is not found aborts the run (the mutant was never
#     applied, so a "kill" would be a lie);
#   * a mutant whose test PASSES is reported as SURVIVED and the run exits
#     non-zero;
#   * the source file is restored from a backup in a trap, so an interrupted
#     run cannot leave a mutant in the tree.
#
# Usage, from the repository root:
#   openspec/changes/archive/2026-09-03-external-source-resolution/mutants.sh
#
set -uo pipefail

cd "$(dirname "$0")/../../../.." || exit 1
# Fail closed on the path: a wrong depth would make every mutant inapplicable,
# which must not read as a clean run.
[[ -x ./mvnw ]] || { echo "ABORT: not at the repository root"; exit 2; }

MVN=(./mvnw -o -q -Dspotless.check.skip=true -Dcheckstyle.skip=true
     -Dskip.ui.verify=true -Dskip.pnpm=true -Dskip.installnodenpm=true
     -Dsurefire.failIfNoSpecifiedTests=false test)

MAIN=src/main/java/dev/skillsgateway/server/ingestion
SURVIVORS=0
KILLED=0

restore() {
    if [[ -n "${BACKUP:-}" && -f "${BACKUP}" ]]; then
        cp "${BACKUP}" "${TARGET}"
        rm -f "${BACKUP}"
    fi
}
trap restore EXIT INT TERM

# mutate <file> <search> <replace> <test-selector> <description>
mutate() {
    TARGET="$1"
    local search="$2" replace="$3" selector="$4" description="$5"
    BACKUP="$(mktemp)"
    cp "${TARGET}" "${BACKUP}"

    python3 - "${TARGET}" "${search}" "${replace}" <<'PY' || { echo "ABORT: mutant not applicable"; exit 2; }
import sys
path, search, replace = sys.argv[1:4]
body = open(path).read()
if body.count(search) != 1:
    sys.stderr.write("search string appears %d times, expected exactly 1\n" % body.count(search))
    raise SystemExit(1)
open(path, "w").write(body.replace(search, replace))
PY

    printf '%-72s' "${description}"
    if "${MVN[@]}" -Dtest="${selector}" >/dev/null 2>&1; then
        echo "SURVIVED"
        SURVIVORS=$((SURVIVORS + 1))
    else
        echo "killed"
        KILLED=$((KILLED + 1))
    fi
    restore
}

echo "=== manual mutation: external-source-resolution ==="

mutate "${MAIN}/SourceAddressPolicy.java" \
    'if (unwrapped.isLinkLocalAddress()) {' \
    'if (unwrapped.isLinkLocalAddress() && !allowPrivateNetworks) {' \
    'SourceAddressPolicyTests' \
    'link-local becomes configurable (metadata endpoint reachable)'

mutate "${MAIN}/SourceAddressPolicy.java" \
    'for (InetAddress address : addresses) {' \
    'for (InetAddress address : addresses.subList(0, 1)) {' \
    'SourceAddressPolicyTests' \
    'only the first resolved address is validated'

mutate "${MAIN}/SourceAddressPolicy.java" \
    'if (bytes.length != 16) {' \
    'if (bytes.length != 16 || true) {' \
    'SourceAddressPolicyTests' \
    'IPv4-mapped and -compatible addresses are not unwrapped'

mutate "${MAIN}/SourceUrlPolicy.java" \
    'if (!origin.host().equals(target.host())) {' \
    'if (false) {' \
    'SourceUrlPolicyTests' \
    'a redirect may leave the origin host'

mutate "${MAIN}/SourceUrlPolicy.java" \
    'String ambiguous = ambiguity(parsed.host());' \
    'String ambiguous = null;' \
    'SourceUrlPolicyTests' \
    'ambiguous address literals are accepted'

mutate "${MAIN}/SourceUrlPolicy.java" \
    'if (parsed.credentials()) {' \
    'if (false) {' \
    'SourceUrlPolicyTests' \
    'embedded credentials are accepted'

mutate "${MAIN}/ResolutionBudget.java" \
    'long closure = closureInflatedBytes + measurement.inflatedBytes();' \
    'long closure = measurement.inflatedBytes();' \
    'ResolutionBudgetTests' \
    'the closure budget stops accumulating across sources'

mutate "${MAIN}/ResolutionBudget.java" \
    'if (measurement.largestBlobBytes() > limits.maxBlobBytes().toBytes()) {' \
    'if (false) {' \
    'ResolutionBudgetTests' \
    'the largest-file budget is not enforced'

mutate "${MAIN}/ManifestRewriter.java" \
    'String stillExternal = policy.validate(rewritten);' \
    'String stillExternal = null;' \
    'ManifestRewriterTests' \
    'the GW_0152 post-condition over the rewritten manifest is dropped'

mutate "${MAIN}/ManifestRewriter.java" \
    'commit.setParentId(upstream);' \
    '/* mutant: no parent */' \
    'ManifestRewriterTests' \
    'the composite is not parented on the upstream commit'

mutate "${MAIN}/ManifestRewriter.java" \
    'Pattern.compile("^[a-z0-9][a-z0-9_-]*$")' \
    'Pattern.compile("^[A-Za-z0-9./][A-Za-z0-9._/-]*$")' \
    'ManifestRewriterTests' \
    'plugin names may be paths or contain traversal'

mutate "${MAIN}/ManifestRewriter.java" \
    'message.append("Transformer-Version: ").append(transformerVersion).append(' \
    'message.append("Transformer-Version: ").append("frozen").append(' \
    'ManifestRewriterTests' \
    'the transformer version stops being part of the identity'

mutate "${MAIN}/ExternalSourceResolver.java" \
    'pruneScaffolding(quarantine);' \
    '/* mutant: scaffolding kept */' \
    'ExternalSourceResolutionTests' \
    'fetch scaffolding refs are left behind in quarantine'

mutate "${MAIN}/GuardedHttpConnectionFactory.java" \
    'String refusal = urlPolicy.refuseRedirect(requestUrl, location, redirects + 1);' \
    'String refusal = null;' \
    'ExternalSourceResolutionTests' \
    'redirect targets are not checked before the hop is taken'

mutate "${MAIN}/IngestionService.java" \
    'RefTransitions.write(repo, "refs/snapshots/" + sha.name(), sha);' \
    'RefTransitions.write(repo, "refs/snapshots/" + upstream.name(), upstream);' \
    'ExternalSourceResolutionTests' \
    'the pinned ref is the upstream commit rather than the served one'

echo "=== killed ${KILLED}, survived ${SURVIVORS} ==="
[[ ${SURVIVORS} -eq 0 ]] || exit 1
