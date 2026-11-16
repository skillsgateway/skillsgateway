#!/usr/bin/env bash
# Manual mutation run for marketplace-auto-approval (evidence.md, "Mutation"; acceptance.md, Gauntlet).
# Applies each mutant alone, runs the test class that must kill it, restores, and proves the restore
# with `git diff --exit-code -- src/main`. Fails closed: a mutant that does not apply exactly once, a
# survivor, a run with no test report, or a dirty src/main after restore stops the run nonzero.
#
#   mutants.sh              run every mutant
#   ONLY='M1|M2' mutants.sh run a subset
#   DRY=1 mutants.sh        prove every mutant applies exactly once, running no test
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
git diff --quiet -- src/main || { echo "src/main is dirty; commit first"; exit 2; }
trap 'git checkout -- src/main' EXIT

A=src/main/java/dev/skillsgateway/server/approval
V=src/main/java/dev/skillsgateway/server/vetting
SRC=$A/InternalSources.java
REQ=$A/RequiredVetters.java
POS=$A/AutoApprovalSettingService.java
EYE=$A/FourEyesGate.java
ELI=$A/AutoApprovalEligibility.java
SVC=$A/AutoApprovalService.java
SUR=$V/SurfaceComparison.java
ING=src/main/java/dev/skillsgateway/server/ingestion/IngestionService.java
LOG=src/main/java/dev/skillsgateway/server/admin/AdminAuditLogger.java
known='^M[0-9]+-'

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
killed() { # id test-class(package-relative) expected-method
  local report=target/surefire-reports/TEST-dev.skillsgateway.server.$2.xml
  rm -f "$report"
  if ./mvnw -q -Dskip.ui.verify=true -Dskip.installnodepnpm -Dskip.pnpm -Djacoco.skip=true \
      -Dspotless.check.skip=true -Dcheckstyle.skip=true \
      -Dtest="${2##*.}" -Dsurefire.failIfNoSpecifiedTests=false test > "target/mutant-$1.log" 2>&1; then
    return 1
  fi
  [ -f "$report" ] || { echo "$1: INVALID (no test report; see target/mutant-$1.log)"; exit 4; }
  python3 - "$report" "$3" <<'PY' || { echo "$1: INVALID ($3 did not fail)"; exit 4; }
import sys, xml.etree.ElementTree as ET
report, method = sys.argv[1:]
for case in ET.parse(report).getroot().iter("testcase"):
    if case.get("name", "").split("(")[0] == method and (case.find("failure") is not None or case.find("error") is not None):
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

# --- the URL-prefix matcher and its startup refusals (GW_APPROVAL_0044.2) ---
T=approval.InternalSourcesTests
run M1-unparseable-accepted $T startupRefusesAnUnparseablePrefix "$SRC" \
  'if (prefix == null || prefix.query() != null || prefix.fragment() != null) {' 'if (prefix == null) {'
run M2-multi-tenant-host-wide $T startupRefusesAHostWidePrefixOnAMultiTenantForge "$SRC" \
  'if (MULTI_TENANT_HOSTS.contains(prefix.host())) {' 'if (false) {'
run M3-github-base-host-wide $T startupRefusesAHostWidePrefixOnTheGithubBaseUrlHost "$SRC" \
  'if (base != null && base.host().equals(prefix.host())) {' 'if (false) {'
run M4-hosted-not-internal $T aUrlUnderAnOrganisationPrefixIsInternalAndSoIsAHostedMarketplace "$SRC" \
  'return marketplace.hosted() || isInternal(marketplace.url());' 'return isInternal(marketplace.url());'

# --- required-vetters and its startup refusals (GW_APPROVAL_0044.7) ---
T=approval.RequiredVettersTests
run M5-empty-list-accepted $T startupRefusesAnEmptyList "$REQ" \
  'if (configured.isEmpty()) {' 'if (false) {'
run M6-unknown-name-accepted $T startupRefusesANameThatIsNotAConfiguredVetter "$REQ" \
  'if (name == null || !known.contains(name)) {' 'if (name == null) {'
run M7-every-vetter-false $T unsetRequiresEveryConfiguredVetter "$REQ" \
  'this.everyVetter = configured == null;' 'this.everyVetter = false;'

# --- the automatic-approval and exemption expiry checks, what allows it (GW_APPROVAL_0044.1, 0044.3, 0044.4) ---
T=AutoApprovalSettingTests
run M8-on-without-reason $T turningOnNeedsAReasonAndAnExpiryOfAtMostNinetyDays "$POS" \
  $'        if (reason == null || reason.isBlank()) {\n            throw new AutoApprovalSettingValidationException(\n                    "automatic approval needs' $'        if (false) {\n            throw new AutoApprovalSettingValidationException(\n                    "automatic approval needs'
run M9-expiry-unbounded $T turningOnNeedsAReasonAndAnExpiryOfAtMostNinetyDays "$POS" \
  'if (expiresAt.isAfter(now.plus(limit))) {' 'if (false) {'
run M10-exemption-without-reason $T anExemptionNeedsAReasonAndAnExpiryOfAtMostNinetyDays "$POS" \
  'if (exemption.reason() == null || exemption.reason().isBlank()) {' 'if (false) {'
run M11-exemption-cap-dropped $T theExemptionCapsTheAutomaticApprovalsExpiry "$POS" \
  'expiresAt = earliest(expiresAt, created.expiresAt());' ''
run M12-not-internal-accepted $T turningOnOutsideThePrefixesNeedsAnExemption "$POS" \
  '} else if (!internal) {' '} else if (false) {'
run M13-lapse-still-effective $T aLapsedAutomaticApprovalIsEffectivelyOff "$POS" \
  $'                || stored.expiresAt() == null\n                || !stored.expiresAt().isAfter(now)) {' $'                || stored.expiresAt() == null) {'
run M14-off-keeps-exemption $T turningOffRevokesTheExemption "$POS" \
  'open.ifPresent(exemption -> revoke(marketplace, exemption, actor, now));
            auditLogger.record(actor, marketplace, EVENT_CHANGED, null, "enabled=false");' 'auditLogger.record(actor, marketplace, EVENT_CHANGED, null, "enabled=false");'
run M15-lapse-not-recorded $T anExpiredExemptionMakesAllowedByNoneRecordedOnceAcrossRepeatedAttempts "$POS" \
  '&& repository.recordExpired(open.get().id(), now)) {' '&& false) {'
run M16-exemption-on-internal $T anExemptionIsRefusedForAMarketplaceThatIsAlreadyInternal "$POS" \
  $'            if (internal) {\n                throw new AutoApprovalSettingValidationException(' $'            if (false) {\n                throw new AutoApprovalSettingValidationException('
T=AutoApprovalSettingEnforceTests
run M17-registrant-grant-effective $T aGrantByTheRegistrantAwaitsASecondAdministrator "$POS" \
  '&& !fourEyesGate.grantConflicts(marketplace, stored.setBy()).isEmpty()) {' '&& false) {'
run M18-reconciler-not-exempt $T theEstateReconcilersGrantNeedsNoSecondAdministrator "$EYE" \
  'if (grantor == null || grantor.isBlank() || EstateReconciler.ACTOR.equals(grantor)) {' 'if (grantor == null || grantor.isBlank()) {'

# --- candidacy (GW_APPROVAL_0044.5, 0044.6) ---
T=approval.AutoApprovalEligibilityTests
run M19-deleted-candidate $T aDeletedSnapshotIsNotACandidate "$ELI" 'if (snapshot.deleted()) {' 'if (false) {'
run M20-revoked-candidate $T aSnapshotThatIsNotHeldIsNotACandidate "$ELI" \
  'if (!Snapshot.HELD.equals(snapshot.state())) {' 'if (Snapshot.APPROVED.equals(snapshot.state())) {'
run M21-older-candidate $T anOlderHeldSnapshotIsNotACandidate "$ELI" 'if (snapshot.id() != newestId) {' 'if (false) {'
run M22-closure-member-candidate $T oneClosureMemberKeepsItHeldWhateverItsUrl "$ELI" \
  'if (closureMembers > 0) {' 'if (closureMembers > 1) {'

# --- the vetting predicate (GW_APPROVAL_0044.7) ---
run M23-no-run-qualifies $T noRunKeepsItHeld "$ELI" \
  $'        if (run == null) {\n            return refused("no-run' $'        if (run == null) {\n            return new Vetting(null, null, List.of(), List.of());\n        }\n        if (false) {\n            return refused("no-run'
run M24-undetermined-in-force $T aChainNotInForceKeepsItHeld "$ELI" \
  'if (inputs.chain() != ChainStaleness.State.IN_FORCE) {' 'if (inputs.chain() == ChainStaleness.State.SUPERSEDED) {'
run M25-pending-ran $T aVetterThatDidNotRunKeepsItHeldWhateverRequiredVettersSays "$ELI" \
  'EnumSet.of(VerdictState.NOT_REACHED, VerdictState.ERROR, VerdictState.PENDING);' 'EnumSet.of(VerdictState.NOT_REACHED, VerdictState.ERROR);'
run M26-unset-ignores-unnamed $T unsetRequiredVettersHoldsOnADisabledVerdictFromAVetterItDoesNotName "$ELI" \
  'if (inputs.everyVetter() || inputs.required().contains(verdict.vetter())) {' 'if (inputs.required().contains(verdict.vetter())) {'
run M27-named-disabled-ignored $T aRequiredVetterDisabledKeepsItHeld "$ELI" \
  'if (inputs.everyVetter() || inputs.required().contains(verdict.vetter())) {' 'if (inputs.everyVetter()) {'
run M28-absent-required-passes $T aRequiredVetterAbsentFromTheChainKeepsItHeld "$ELI" \
  'if (run.stateOf(required).isEmpty()) {' 'if (false) {'
run M29-blocked-qualifies $T anUncoveredHighFindingKeepsItHeld "$ELI" \
  'if (outcome != VettingChain.Outcome.CLEAR && outcome != VettingChain.Outcome.CLEAR_WITH_WAIVERS) {' 'if (false) {'
run M30-waiver-rule-critical-only $T aWaivedHighFindingAbsentFromTheBaselineInThatBlobKeepsItHeld "$ELI" \
  'compareTo(Severity.HIGH) >= 0' 'compareTo(Severity.CRITICAL) >= 0'
run M31-baseline-ignores-blob $T aWaivedHighFindingAbsentFromTheBaselineInThatBlobKeepsItHeld "$ELI" \
  ' && finding.content().equals(prior.content())' ''
run M32-baseline-null-blob $T aWaivedHighFindingAbsentFromTheBaselineInThatBlobKeepsItHeld "$ELI" \
  'if (baseline == null || finding.content() == null) {' 'if (baseline == null) {'
run M33-unread-surface-passes $T anUnreadSurfaceKeepsItHeldAtAnySeverity "$ELI" \
  'if (UNREAD_SURFACE.contains(finding.id())) {' 'if (false) {'
run M34-disabled-unnamed $T anotherVetterDisabledUnderARelaxedListQualifiesAndIsNamed "$ELI" \
  'disabled.add(verdict.vetter());' ''

# --- the executable surface (GW_APPROVAL_0044.8) ---
T=vetting.SurfaceComparisonTests
run M35-mode-check $T modesOtherThanARegularFileAreNotInert "$SUR" 'if (mode != FileMode.REGULAR_FILE) {' 'if (false) {'
run M36-mode-change $T aModeChangeToARegularFileIsNotInert "$SUR" \
  'if (was != FileMode.MISSING && was != mode) {' 'if (false) {'
run M37-dependency-manifest $T aDependencyManifestOrLockfileIsNotInert "$SUR" \
  'if (DependencyManifests.isManifest(path) || DependencyManifests.isLockfile(name)) {' 'if (false) {'
run M38-launch-target $T aLaunchTargetIsNotInertWhateverTheExtension "$SUR" 'if (launchTargets.contains(path)) {' 'if (false) {'
run M39-shebang $T aShebangIsAScriptWhateverTheExtension "$SUR" \
  'if (startsWithShebang(repo, tree.getObjectId(1))) {' 'if (false) {'
run M40-descriptive-any-key $T aManifestOrPluginJsonKeyOffTheDescriptiveListIsNotInert "$SUR" \
  'if (!allowed.contains(names.next())) {' 'if (names.next() == null) {'
run M41-configuration-file-inert $T aChangedConfigurationFileIsChangedEvenWhereNoReaderReadsIt "$SUR" \
  'return path + (covered.contains(path) ? " (component file)" : " (configuration file)");' 'continue;'
run M84-component-file-inert $T aChangedHookIsChanged "$SUR" \
  'return path + (covered.contains(path) ? " (component file)" : " (configuration file)");' 'continue;'
run M42-unknown-extension-inert $T anUnknownExtensionIsAScript "$SUR" \
  'return path + " (not an inert extension)";' 'continue;'
run M43-hook-problems $T anUnreadableHooksFileIsUnreadable "$SUR" 'if (!components.hookProblems().isEmpty()) {' 'if (false) {'
run M44-unscanned-module $T anUnscannedHookModuleIsUnreadable "$SUR" 'if (!module.unscanned().isEmpty()) {' 'if (false) {'
run M45-malformed-frontmatter $T malformedFrontmatterIsUnreadable "$SUR" 'if (parsed.malformed()) {' 'if (false) {'
run M46-unparseable-manifest $T anUnparseableManifestIsUnreadable "$SUR" \
  'return Read.failed(MANIFEST_PATH + " is not valid JSON");' 'manifest = null;'
run M47-max-plugins $T pluginsPastTheLimitAreUnreadable "$SUR" \
  'if (entries.size() > ManifestPolicy.MAX_PLUGINS) {' 'if (false) {'
run M48-max-roots $T pluginRootsPastTheLimitAreUnreadable "$SUR" \
  'if (roots.size() > ExecutableSurfaceVetter.MAX_PLUGIN_ROOTS) {' 'if (false) {'
run M49-declarations-ignored $T changedAllowedToolsIsChanged "$SUR" \
  'return declarationsDiffer(before.declarations(), after.declarations());' 'return Result.same();'
run M50-removed-declaration $T aRemovedDeclarationIsChanged "$SUR" \
  $'        for (Map.Entry<String, String> declaration : before.entrySet()) {\n            if (!after' $'        for (Map.Entry<String, String> declaration : before.entrySet()) {\n            if (false && !after'

# --- one attempt: mode, re-vetting, deduplication, gates, race (GW_APPROVAL_0044.9–0044.12) ---
T=approval.AutoApprovalServiceTests
run M51-off-evaluates $T underOffNothingIsEvaluated "$SVC" \
  $'        if (mode == AutoMode.OFF) {\n            return Outcome.OFF;\n        }\n        Snapshot snapshot' $'        Snapshot snapshot'
run M52-dry-run-publishes $T underDryRunEveryCheckRunsAndNothingIsPublished "$SVC" \
  'if (mode == AutoMode.DRY_RUN) {' 'if (false) {'
run M53-revet-ignored $T withReVettingOffTheAttemptSkips "$SVC" \
  'if (!properties.vetting().revet().enabled()) {' 'if (false) {'
run M54-skip-not-deduplicated $T aRepeatedSkipIsLedgeredOnce "$SVC" \
  'if (snapshotRepository.recordAutoApprovalSkip(snapshot.id(), reason)) {' 'if (true) {'
run M55-race-not-ledgered $T whenAPersonDecidesFirstTheAttemptRecordsASkipAndPublishesNothing "$SVC" \
  $'            if (refused instanceof IllegalStateException) {\n                // A person' $'            if (false) {\n                // A person'
run M56-gate-reason-unstable $T aGateRefusalLeavesItHeldWithAStableReason "$SVC" \
  'String name = refused.getClass().getSimpleName().replaceFirst("Exception$", "");' 'String name = String.valueOf(refused.getMessage());'
run M57-tick-attempts-nothing $T aTooYoungSnapshotIsApprovedOnALaterTickOnceOfAge "$SVC" \
  'for (long marketplaceId : settingRepository.enabledMarketplaces()) {' 'for (long marketplaceId : List.<Long>of()) {'
run M58-no-approval-evidence $T underOnAnEligibleSnapshotIsApprovedThroughTheOneApprovalPath "$SVC" \
  'recorder.recordApproved(approved, ACTOR);' ''

# --- end to end (GW_APPROVAL_0044) ---
T=AutoApprovalTests
run M59-ingestion-does-not-trigger $T anInternalMarketplaceServesAMarkdownOnlyUpdateWithNoPerson "$ING" \
  'events.publishEvent(new HeldSnapshotChanged(marketplace.id(), "ingested"));' ''
run M60-auto-approver-human $T anInternalMarketplaceServesAMarkdownOnlyUpdateWithNoPerson "$LOG" \
  $',\n            // Approves with no person (GW_APPROVAL_0044.11).\n            AutoApprovalService.ACTOR);' ');'
run M61-closure-ignored-end-to-end $T theSameUpdateWithAnExternalSourceAddedStaysHeld "$ELI" \
  'if (closureMembers > 0) {' 'if (false) {'
run M62-no-human-path-evidence $T anAutomaticApprovalWritesTheDecisionEntryAndOneWebhookAsAPersonsDoes "$SVC" \
  'recorder.recordApproved(approved, ACTOR);' ''

# --- re-vetting recalls what no person approved; bulk withdrawal (GW_APPROVAL_0044.13, 0044.14) ---
REV=src/main/java/dev/skillsgateway/server/vetting/RevetService.java
WDR=$A/AutoApprovalWithdrawal.java
RVK=$A/RevocationService.java
REP=src/main/java/dev/skillsgateway/server/persistence/SnapshotRepository.java
CTL=$A/AutoApprovalSettingController.java
T=AutoApprovalTests
run M63-auto-follows-warn $T underWarnAViolationRevokesAnAutomaticApprovalAndOnlyRecordsAPersons "$REV" \
  'return AutoApprovalService.ACTOR.equals(snapshot.decidedBy())' 'return false'
run M64-person-enforced $T underWarnAViolationRevokesAnAutomaticApprovalAndOnlyRecordsAPersons "$REV" \
  $'                ? SkillsGatewayProperties.RevetMode.ENFORCE\n                : properties.mode();' $'                ? SkillsGatewayProperties.RevetMode.ENFORCE\n                : SkillsGatewayProperties.RevetMode.ENFORCE;'
run M65-since-ignored $T bulkWithdrawalRevokesExactlyTheAutomaticApprovalsSinceATimeNewestFirst "$REP" \
  ' AND decided_at >= :since AND deleted_at IS NULL ORDER BY decided_at DESC' ' AND :since IS NOT NULL AND deleted_at IS NULL ORDER BY decided_at DESC'
run M66-approver-ignored $T bulkWithdrawalRevokesExactlyTheAutomaticApprovalsSinceATimeNewestFirst "$REP" \
  'state = :approved::snapshot_state AND decided_by = :approver' 'state = :approved::snapshot_state AND :approver IS NOT NULL'
run M67-oldest-first $T bulkWithdrawalRevokesExactlyTheAutomaticApprovalsSinceATimeNewestFirst "$REP" \
  'ORDER BY decided_at DESC, id DESC' 'ORDER BY decided_at ASC, id ASC'
run M68-return-to-a-withdrawn $T bulkWithdrawalRevokesExactlyTheAutomaticApprovalsSinceATimeNewestFirst "$RVK" \
  'Set<Long> excluded = new HashSet<>(alsoWithdrawing);' 'Set<Long> excluded = new HashSet<>();'
run M69-non-tip-takes-the-choice $T bulkWithdrawalRevokesExactlyTheAutomaticApprovalsSinceATimeNewestFirst "$WDR" \
  'served(snapshot, names) ? request.serveAfter() : ServeAfter.NOTHING' 'request.serveAfter()'
run M70-withdraw-not-admin-only $T bulkWithdrawalRevokesExactlyTheAutomaticApprovalsSinceATimeNewestFirst "$CTL" \
  $'        roleService.requireAdmin(authentication);\n        return withdrawal.withdraw' $'        return withdrawal.withdraw'
run M71-precheck-dropped $T bulkWithdrawalThatCannotBeHonouredWithdrawsNothing "$WDR" \
  'if (request.serveAfter() == ServeAfter.PREVIOUS_APPROVED) {' 'if (false) {'
run M72-reason-optional $T bulkWithdrawalThatCannotBeHonouredWithdrawsNothing "$WDR" \
  'if (request.reason() == null || request.reason().isBlank()) {' 'if (false) {'
run M73-race-fails-the-request approval.AutoApprovalWithdrawalTests aSnapshotWithdrawnMeanwhileIsSkippedAndTheRestAreStillWithdrawn "$WDR" \
  $'            } catch (NotApprovedException withdrawnMeanwhile) {\n                continue;' $'            } catch (NotApprovedException withdrawnMeanwhile) {\n                throw withdrawnMeanwhile;'

# --- declared automatic approval (GW_ESTATE_0006) ---
EST=src/main/java/dev/skillsgateway/server/estate/EstateReconciler.java
T=AutoApprovalTests
run M74-exemption-declared $T aDeclaredAutomaticApprovalIsAppliedByTheReconcilerAndAnInvalidOneFailsItsEntry "$EST" \
  'if (declared.exemption() != null && !declared.exemption().isEmpty()) {' 'if (false) {'
run M75-registered-before-checked $T aDeclaredAutomaticApprovalIsAppliedByTheReconcilerAndAnInvalidOneFailsItsEntry "$EST" \
  'autoApproval.ifPresent(request -> asEntryFailure(() -> settingService.checkRegistration(' 'autoApproval.filter(request -> false).ifPresent(request -> asEntryFailure(() -> settingService.checkRegistration('
run M76-lapsed-reapplied $T aLapsedDeclaredAutomaticApprovalIsOffUntilTheDeclarationChanges "$EST" \
  $'.truncatedTo(ChronoUnit.MICROS)\n                                        .equals(stored.expiresAt())));' $'.truncatedTo(ChronoUnit.MICROS)\n                                        .equals(null)));'
run M77-reason-not-compared $T aDeclaredAutomaticApprovalIsAppliedByTheReconcilerAndAnInvalidOneFailsItsEntry "$EST" \
  '&& declared.reason().trim().equals(stored.reason())' ''
run M78-not-the-reconciler $T aDeclaredAutomaticApprovalIsAppliedByTheReconcilerAndAnInvalidOneFailsItsEntry "$EST" \
  'asEntryFailure(() -> settingService.setDeclared(name, declared));' 'asEntryFailure(() -> settingService.set(name, declared, "root"));'

# --- adversarial pass ---
CCG=src/main/java/dev/skillsgateway/server/approval/ClosureCompletenessGate.java
run M79-closure-gate-open $T aPluginDisguisedAsAGraftWithNoClosureIsRefusedByTheClosureGate "$CCG" \
  'if (!discrepancies.isEmpty()) {' 'if (false) {'

# --- review findings ---
ADM=src/main/java/dev/skillsgateway/server/admin/AdminController.java
T=MachineApiScopeTests
run M80-machine-registers-a-grant $T a_registration_credential_cannot_turn_on_automatic_approval_and_registers_nothing "$ADM" \
  '&& authentication instanceof MachineApiAuthentication) {' '&& false) {'
WDR=$A/AutoApprovalWithdrawal.java
T=AutoApprovalTests
run M81-recall-leaves-it-on $T bulkWithdrawalTurnsAutomaticApprovalOffWhereItWithdrew "$WDR" \
  'if (settingService.turnOffAfterWithdrawal(name, administrator, "withdrawn: " + request.reason())) {' 'if (false) {'

T=approval.AutoApprovalServiceTests
run M82-unreadable-as-changed $T aSurfaceThatCannotBeComparedKeepsTheSnapshotHeldUnderItsOwnReason "$SVC" \
  'return "surface-unreadable: the surface could not be compared ("' 'return "surface-changed: the surface could not be compared ("'
T=admin.RegistrationAutoApprovalTests
run M83-failed-setting-fails-registration $T aSettingThatFailsAfterRegistrationIsReportedOnTheRegisteredMarketplace "$ADM" \
  '} catch (RuntimeException e) {' '} catch (UnsupportedOperationException e) {'
T=approval.AutoApprovalServiceTests
run M85-no-recheck-before-approve $T aSettingTurnedOffDuringTheAttemptPublishesNothing "$SVC" \
  'if (stillAllowedBy != allowedBy) {' 'if (false) {'
run M86-failure-as-gate-refusal $T aFailureToPublishIsNotRecordedAsAGateRefusal "$SVC" \
  'return (failure ? "approval-failed: " : "gate-refused: ") + code;' 'return "gate-refused: " + code;'
T=AutoApprovalTests
run M87-estate-does-not-attempt $T aDeclaredAutomaticApprovalAttemptsTheHeldSnapshot "$EST" \
  'autoApprovalService.attemptNewest(name, "auto-approval-declared");' ''
T=AutoApprovalSettingEnforceTests
run M88-reconciler-name-by-sign-in $T aPersonSignedInUnderTheReconcilersNameCannotGrant "$POS" \
  'if (EstateReconciler.ACTOR.equals(actor)) {' 'if (false) {'
[ "$ran" -gt 0 ] || { echo "no mutant selected"; exit 5; }
if [[ -n "${DRY:-}" ]]; then echo "$ran mutants apply"; else echo "$ran mutants run, all killed"; fi
