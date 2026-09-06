#!/usr/bin/env bash
#
# Manual mutation testing for external-source-closure (old-coder gauntlet).
#
# The project has no mutation-testing plugin, so this is the hand-rolled
# procedure: introduce one plausible bug at a time, run the test that should
# catch it, and require that it fails. A mutant that survives is a hole in the
# suite and is reported as one.
#
# It fails closed, on purpose and in every direction:
#   * a search string that is not found exactly once aborts the run (the mutant
#     was never applied, so a "kill" would be a lie);
#   * a mutant whose test PASSES is reported as SURVIVED and the run exits
#     non-zero;
#   * the source file is restored from a backup in a trap, so an interrupted
#     run cannot leave a mutant in the tree.
#
# Usage, from the repository root:
#   openspec/changes/external-source-closure/mutants.sh
# (or its archived path under openspec/changes/archive/).
#
set -uo pipefail

cd "$(dirname "$0")/../../.." || exit 1
if [[ ! -x ./mvnw ]]; then
    # Archived: one directory deeper.
    cd .. || exit 1
fi
# Fail closed on the path: a wrong depth would make every mutant inapplicable,
# which must not read as a clean run.
[[ -x ./mvnw ]] || { echo "ABORT: not at the repository root"; exit 2; }

MVN=(./mvnw -o -q -Dspotless.check.skip=true -Dcheckstyle.skip=true
     -Dskip.ui.verify=true -Dskip.pnpm=true -Dskip.installnodenpm=true
     -Dsurefire.failIfNoSpecifiedTests=false test)

MAIN=src/main/java/dev/skillsgateway/server
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

    printf '%-78s' "${description}"
    if "${MVN[@]}" -Dtest="${selector}" >/dev/null 2>&1; then
        echo "SURVIVED"
        SURVIVORS=$((SURVIVORS + 1))
    else
        echo "killed"
        KILLED=$((KILLED + 1))
    fi
    restore
}

echo "=== manual mutation: external-source-closure ==="

# --- the closure value (GW_0163)

mutate "${MAIN}/ingestion/SnapshotClosure.java" \
    'List<String> lines = members.stream().map(Member::canonical).sorted().toList();' \
    'List<String> lines = members.stream().map(Member::canonical).toList();' \
    'SnapshotClosureDigestTests' \
    'the digest depends on member order'

mutate "${MAIN}/ingestion/SnapshotClosure.java" \
    '            frame(out, declaredRef);' \
    '            frame(out, (String) null);' \
    'SnapshotClosureDigestTests' \
    'the declared ref stops being an input to the digest'

mutate "${MAIN}/ingestion/SnapshotClosure.java" \
    'out.append("-\n");' \
    'out.append("0:\n");' \
    'SnapshotClosureDigestTests' \
    'null and the empty string frame identically'

# --- recording (GW_0163)

mutate "${MAIN}/persistence/SnapshotRepository.java" \
    'if (closure != null && !closure.isEmpty()) {' \
    'if (false) {' \
    'SnapshotClosureTests' \
    'the closure is never written with the snapshot'

mutate "${MAIN}/ingestion/IngestionService.java" \
    'marketplace.id(), sha.name(), upstream.name(), state, violation, actor, served.closure());' \
    'marketplace.id(), sha.name(), sha.name(), state, violation, actor, served.closure());' \
    'SnapshotClosureTests' \
    'upstream_sha records the served commit instead of the upstream one'

mutate "${MAIN}/policy/SnapshotFactsService.java" \
    'pluginFacts.put("origin", member == null ? "local" : "external");' \
    'pluginFacts.put("origin", "local");' \
    'SnapshotClosureTests' \
    'every plugin is a local plugin to the policy gate'

mutate "${MAIN}/persistence/SnapshotClosureRepository.java" \
    '+ " AND (:resolvedSha::text IS NULL OR m.resolved_sha = :resolvedSha)"' \
    '+ ""' \
    'SnapshotClosureTests' \
    'the blast-radius query ignores the resolved commit'

# --- the completeness gate (GW_0164)

mutate "${MAIN}/approval/ApprovalService.java" \
    'requireCompleteClosure(current, marketplace, reviewer);' \
    '/* mutant: the gate is not consulted */' \
    'ClosureCompletenessTests' \
    'approval never consults the closure gate'

mutate "${MAIN}/approval/ApprovalService.java" \
    '"closure-incomplete: " + String.join("; ", refused.discrepancies()));' \
    '"closure-incomplete: ");' \
    'ClosureCompletenessTests' \
    'the ledger entry names no discrepancy'

mutate "${MAIN}/approval/ClosureCompletenessGate.java" \
    'if (!members.containsKey(graft.getKey())) {' \
    'if (false) {' \
    'ClosureCompletenessTests' \
    'a manifest graft with no closure member is accepted'

mutate "${MAIN}/approval/ClosureCompletenessGate.java" \
    'if (!grafted.containsKey(path)) {' \
    'if (false) {' \
    'ClosureCompletenessTests' \
    'a closure member the manifest does not declare is accepted'

mutate "${MAIN}/approval/ClosureCompletenessGate.java" \
    'if (!ObjectId.isId(member.resolvedSha())) {' \
    'if (false) {' \
    'ClosureCompletenessTests' \
    'a member with no usable resolved commit is accepted'

mutate "${MAIN}/approval/ClosureCompletenessGate.java" \
    '} else if (!tree.name().equals(member.treeSha())) {' \
    '} else if (false) {' \
    'ClosureCompletenessTests' \
    'a member whose recorded tree differs from the grafted tree is accepted'

mutate "${MAIN}/approval/ClosureCompletenessGate.java" \
    'for (String path : trees.keySet()) {' \
    'for (String path : List.<String>of()) {' \
    'ClosureCompletenessTests' \
    'content under the reserved directory that no member accounts for is accepted'

mutate "${MAIN}/approval/ClosureCompletenessGate.java" \
    'if (plugins == null || !plugins.isArray()) {' \
    'if (true) {' \
    'ClosureCompletenessTests' \
    'the served manifest is never read'

echo "=== killed ${KILLED}, survived ${SURVIVORS} ==="
[[ ${SURVIVORS} -eq 0 ]] || exit 1
