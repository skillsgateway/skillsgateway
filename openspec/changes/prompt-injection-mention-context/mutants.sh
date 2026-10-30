#!/usr/bin/env bash
# Manual mutation run for prompt-injection-mention-context (evidence.md, "Mutation").
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

R=src/main/java/dev/skillsgateway/server/vetting/ContentRules.java
V=src/main/java/dev/skillsgateway/server/vetting/PromptInjectionVetter.java
known='^M([1-9]|1[0-3])-'


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

T=vetting.PromptInjectionMentionContextTests
SILENT=vocabularyAdviceEmojiAndTraversalStringsRaiseNothing
MENTION=aQuotedOrProhibitedMarkerIsReportedOnceAtMedium
PAYLOAD=thePayloadsStillFail
run M1-never-a-mention $T $MENTION "$R" \
  'boolean mention = rule.mentionable() && mention(text, matcher.start());' 'boolean mention = false;'
run M2-always-a-mention $T $PAYLOAD "$R" \
  'boolean mention = rule.mentionable() && mention(text, matcher.start());' 'boolean mention = rule.mentionable();'
run M3-prohibition-ignored $T $MENTION "$R" \
  'if (PROHIBITION.matcher(clause).find()) {' 'if (false) {'
run M4-quote-without-example $T $PAYLOAD "$R" \
  'return quoted && EXAMPLE.matcher(clause).find();' 'return quoted;'
run M5-mention-outranks-plain $T $PAYLOAD "$R" \
  '? next : kept);' '? kept : kept);'
run M6-clause-unbounded $T $PAYLOAD "$R" \
  'if (".!?;:".indexOf(c) >= 0 && Character.isWhitespace(next) && !abbreviation(text, i - 1)) {' 'if (false) {'
run M7-eg-is-a-break $T $MENTION "$R" \
  '&& !abbreviation(text, i - 1)) {' ') {'
run M8-tell-them-conceals $T $SILENT "$V" \
  '+ "(?!\\s+(?:they|to)\\b)",' '+ "",'
run M9-condition-conceals $T $SILENT "$V" \
  '"(?:(?![.!?;:]\\s)(?!" + word("only|unless|if") + ")[^\\n]){0,30}"' '"(?:(?![.!?;:]\\s)[^\\n]){0,30}"'
run M10-emoji-joiner-hidden $T $SILENT "$V" \
  'if (invisible(codePoint) && !emojiJoiner(text, index)) {' 'if (invisible(codePoint)) {'
run M11-every-joiner-allowed $T $PAYLOAD "$V" \
  'return pictographic(before) && pictographic(text.codePointAt(index + 1));' 'return true;'
run M12-traversal-is-a-credential $T $SILENT "$V" \
  '(?<!\\.\\.)/etc/passwd|' '/etc/passwd|'
run M13-disclosure-any-word $T $SILENT "$V" \
  '+ DISCLOSURE_FILLER' '+ "\\S+"'

[ "$ran" -gt 0 ] || { echo "no mutant selected"; exit 5; }
if [[ -n "${DRY:-}" ]]; then echo "$ran mutants apply"; else echo "$ran mutants run, all killed"; fi
