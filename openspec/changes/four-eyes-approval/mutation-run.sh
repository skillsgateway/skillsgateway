#!/usr/bin/env bash
# Manual mutation run for the four-eyes rule (old-coder gauntlet).
#
# Each mutant is a plausible bug someone could introduce into this change while
# leaving the code looking reasonable. The suite must kill every one. The point
# is not the score: a test claiming to prove "the ingestion actor cannot
# approve" would still pass if the rule silently stopped comparing, and that is
# exactly what these mutants ask.
#
# Fail-closed by construction:
#   * a mutant is applied by editing a byte-for-byte backup copy taken at the
#     start and written back afterwards -- never by `git checkout`, which would
#     restore the file to the index and silently discard uncommitted work;
#   * an anchor that does not match is a hard failure, not a skipped mutant: a
#     mutant that was never applied would otherwise score as "killed" by a suite
#     that never saw it;
#   * a surviving mutant is reported and the script exits non-zero;
#   * every file's SHA-256 is compared against its pre-run value at the end, so
#     a half-restored tree cannot masquerade as a clean run.
#
# Usage, from the repository root (Docker/Podman must be reachable):
#   export DOCKER_HOST="unix://$(podman machine inspect | jq -r '.[0].ConnectionInfo.PodmanSocket.Path')"
#   export TESTCONTAINERS_RYUK_DISABLED=true
#   bash openspec/changes/four-eyes-approval/mutation-run.sh
set -uo pipefail

GATE=src/main/java/dev/skillsgateway/server/approval/FourEyesGate.java
SERVICE=src/main/java/dev/skillsgateway/server/approval/ApprovalService.java
CONTROLLER=src/main/java/dev/skillsgateway/server/admin/AdminController.java
REPO=src/main/java/dev/skillsgateway/server/persistence/SnapshotRepository.java
REGISTRATION=src/main/java/dev/skillsgateway/server/admin/MarketplaceRegistrationService.java

TESTS='FourEyesTests,FourEyesEnforceTests'
FILES=("$GATE" "$SERVICE" "$CONTROLLER" "$REPO" "$REGISTRATION")

BACKUP="$(mktemp -d "${TMPDIR:-/tmp}/four-eyes-mutants.XXXXXX")"
trap 'rm -rf "$BACKUP"' EXIT

declare -A ORIGINAL_HASH
for file in "${FILES[@]}"; do
  mkdir -p "$BACKUP/$(dirname "$file")"
  cp "$file" "$BACKUP/$file"
  ORIGINAL_HASH["$file"]="$(shasum -a 256 "$file" | cut -d' ' -f1)"
done

survivors=0
total=0

restore() { cp "$BACKUP/$1" "$1"; }

# apply <file> <perl-expression> — edits in place and fails hard when nothing matched.
apply() {
  local file="$1" expr="$2" before after
  before="$(shasum -a 256 "$file")"
  perl -0pi -e "$expr" "$file"
  after="$(shasum -a 256 "$file")"
  if [[ "$before" == "$after" ]]; then
    echo "FATAL: mutant anchor did not apply in $file" >&2
    restore "$file"
    exit 2
  fi
}

mutant() {
  local name="$1" file="$2" expr="$3"
  total=$((total + 1))
  apply "$file" "$expr"
  if ./mvnw -q test -Dtest="$TESTS" -DfailIfNoTests=false > "$BACKUP/run.log" 2>&1; then
    echo "SURVIVED  $name"
    survivors=$((survivors + 1))
  else
    echo "killed    $name"
  fi
  restore "$file"
}

# M1 — the trigger constants stop being excluded: a snapshot the sweep ingested
#      becomes unapprovable by the identity the sweep acts under.
mutant "M1 non-human actors no longer excluded" "$GATE" \
  's/return actor != null && !NON_HUMAN_ACTORS\.contains\(actor\) && actor\.equals\(reviewer\);/return actor != null \&\& actor.equals(reviewer);/'

# M2 — enforcement stops refusing: conflicts are still computed and reported,
#      and every enforce-mode approval goes through anyway.
mutant "M2 enforce mode never throws" "$GATE" \
  's/if \(!conflicts\.isEmpty\(\) && enforcing\(\)\) \{/if (false) {/'

# M3 — the waiver clause disappears: self-authored waivers stop conflicting.
mutant "M3 waiver authorship no longer conflicts" "$GATE" \
  's/if \(sameIdentity\(suppression\.approvedBy\(\), reviewer\) && seen\.add\(suppression\.waiverId\(\)\)\) \{/if (false) {/'

# M4 — the registrant clause disappears.
mutant "M4 registrant no longer conflicts" "$GATE" \
  's/if \(marketplace != null && sameIdentity\(marketplace\.registeredBy\(\), reviewer\)\) \{/if (false) {/'

# M5 — the gate is still consulted but its answer is dropped in the approval
#      path: what "the rule exists and nothing calls it" looks like.
mutant "M5 approval ignores the gate" "$SERVICE" \
  's/conflicts = requireFourEyes\(current, marketplace, applied, reviewer\);/conflicts = List.of();/'

# M6 — the ingestion actor is accepted by the API and then not persisted.
mutant "M6 ingestion actor not persisted" "$REPO" \
  's/\.param\("ingestedBy", ingestedBy\)/.param("ingestedBy", (String) null)/'

# M7 — the registrant is accepted by the API and then not persisted.
mutant "M7 registrant not persisted" "$REGISTRATION" \
  's/                actor\);/                (String) null);/'

# M8 — warn mode stops writing the conflict to the ledger, which is the whole of
#      what warn mode does.
mutant "M8 warn mode writes no ledger entry" "$CONTROLLER" \
  's/if \(!approved\.fourEyesConflicts\(\)\.isEmpty\(\)\) \{/if (false) {/'

for file in "${FILES[@]}"; do
  if [[ "$(shasum -a 256 "$file" | cut -d' ' -f1)" != "${ORIGINAL_HASH[$file]}" ]]; then
    echo "FATAL: $file was not restored to its pre-run content" >&2
    exit 2
  fi
done

echo "----"
echo "$((total - survivors))/$total mutants killed"
[[ $survivors -eq 0 ]]
