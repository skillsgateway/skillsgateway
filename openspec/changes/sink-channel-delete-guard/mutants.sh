#!/usr/bin/env bash
# Manual mutation run for sink-channel-delete-guard (acceptance.md, "Gauntlet plan").
# Applies each mutant alone, runs the tests that must kill it, restores, and proves the restore
# with `git diff --exit-code`. Fails closed: a mutant that does not apply, a survivor, or a dirty
# tree after restore stops the run with a nonzero exit.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
git diff --quiet || { echo "tree is dirty; commit first"; exit 2; }

JAVA_TESTS='SinkChannelGuardTests'
SVC=src/main/java/dev/skillsgateway/server/webhook/WebhookService.java
CTL=src/main/java/dev/skillsgateway/server/webhook/WebhookController.java
EST=src/main/java/dev/skillsgateway/server/estate/EstateReconciler.java
EXP=src/main/java/dev/skillsgateway/server/audit/AuditExportService.java
SQL=src/main/resources/db/migration/V1__init.sql
WEB=src/main/frontend/src/pages/webhooks.tsx

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

# A kill counts only when the named test fails on an assertion; a compile error, a broken
# database or any other test failing is reported as INVALID, never as a kill.
java_killed() { # id expected-test
  local report=target/surefire-reports/dev.skillsgateway.server.$JAVA_TESTS.txt
  rm -f "$report"
  if ./mvnw -q -Dskip.ui.verify=true -Dskip.installnodenpm -Dskip.pnpm -Dtest="$JAVA_TESTS" \
      -Dsurefire.failIfNoSpecifiedTests=false test > "/tmp/mutant-$1.log" 2>&1; then
    return 1
  fi
  [ -f "$report" ] || { echo "$1: INVALID (no test report; see /tmp/mutant-$1.log)"; exit 4; }
  grep -q "$2 .*<<< FAILURE!" "$report" \
    || { echo "$1: INVALID ($2 did not fail on an assertion; see $report)"; exit 4; }
}

ui_killed() { # id expected-test
  if (cd src/main/frontend && npx vitest run --project unit src/pages/webhooks.test.tsx > "/tmp/mutant-$1.log" 2>&1); then
    return 1
  fi
  grep -q "× $2" "/tmp/mutant-$1.log" || { echo "$1: INVALID ($2 did not fail)"; exit 4; }
}

run() { # id kind expected-test file old new
  local id=$1 kind=$2 expected=$3 file=$4
  shift
  mutate "$file" "$4" "$5"
  if "${kind}_killed" "$id" "$expected"; then result=killed; else result=SURVIVED; fi
  git checkout -- "$file"
  git diff --exit-code > /dev/null || { echo "$id: restore left the tree dirty"; exit 3; }
  echo "$id $result"
  [ "$result" = killed ] || exit 1
}

run M1-guard-removed java the_webhooks_api_refuses_to_delete_a_sinks_channel_and_changes_nothing "$SVC" \
  'Optional<AuditSink> sink = sinkRepository.findBySubscriberId(subscriberId);
        if (sink.isPresent()) {' \
  'Optional<AuditSink> sink = sinkRepository.findBySubscriberId(subscriberId);
        if (false && sink.isPresent()) {'
run M2-estate-guard-removed java a_declared_webhook_named_like_a_sink_fails_its_entry_and_leaves_the_channel_alone "$EST" \
  '        webhookService.requireNotSinkChannel(stored.id());
' ''
run M3-fk-cascade java storage_refuses_a_raw_delete_of_a_sinks_channel "$SQL" \
  'REFERENCES webhook_subscribers (id) ON DELETE RESTRICT' 'REFERENCES webhook_subscribers (id) ON DELETE CASCADE'
run M4-no-transaction java removing_a_sink_is_all_or_nothing "$EXP" \
  'this.transactions = new TransactionTemplate(transactionManager);' \
  'this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(5); // PROPAGATION_NEVER: runs without a transaction'
run M5-auditsink-null java the_listing_marks_a_sinks_channel_with_its_sink "$CTL" \
  '.map(subscriber -> view(subscriber, sinks.get(subscriber.id())))' '.map(subscriber -> view(subscriber, null))'
run M6-portal-filter-removed ui a_sinks_channel_is_not_listed_as_a_subscriber_and_its_deliveries_lead_to_the_sink "$WEB" \
  '(subscriber) => !subscriber.auditSink' '(subscriber) => subscriber !== undefined'
# Throwaway mutants proving the regression-armor scenarios can fail (S2, S4).
run T1-guard-refuses-everything java a_lifecycle_subscriber_is_still_deleted "$SVC" \
  '        requireNotSinkChannel(id);
        try {' \
  '        if (true) throw new ResponseStatusException(HttpStatus.CONFLICT, "refused");
        try {'
run T2-channel-left-behind java deleting_a_sink_removes_the_sink_and_its_channel "$EXP" \
  '            subscriberRepository.delete(sink.get().subscriberId());
            return true;' \
  '            return true;'
echo "all mutants killed"
