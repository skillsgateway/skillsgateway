"""Manual mutation pass for remove-marketplace: each mutant is one asserted replacement, run against
MarketplaceRemovalTests, then restored with git checkout. Usage: python3 mutants.py M1-facade-check ..."""
import subprocess, sys, re, pathlib
ROOT = pathlib.Path(subprocess.run(['git', 'rev-parse', '--show-toplevel'], capture_output=True, text=True, check=True, cwd=pathlib.Path(__file__).parent).stdout.strip())
J = 'src/main/java/dev/skillsgateway/server/'
MUTANTS = {
 'M1-facade-check': (J+'facade/GitFacadeConfiguration.java', '&& marketplaceRepository.findByName(marketplace).isEmpty()) {', '&& false) {'),
 'M2-decide-lock': (J+'persistence/SnapshotRepository.java', 'if (!live) {', 'if (false && !live) {'),
 'M3-name-filter': (J+'persistence/MarketplaceRepository.java', '"SELECT * FROM marketplaces WHERE name = :name AND deleted_at IS NULL"', '"SELECT * FROM marketplaces WHERE name = :name"'),
 'M4-pin-guard': (J+'retention/RetentionService.java', 'if (!snapshotRepository.pinnedByAnotherOfTheSameName(snapshot.id())) {', 'if (true) {'),
 'M5-successor-unpublish': (J+'admin/MarketplaceRegistrationService.java', 'storage.unpublish(name, snapshot.sha());', 'snapshot.sha();'),
 'M6-grant-filter': (J+'roles/RoleGrantRepository.java', '" WHERE (g.marketplace_id IS NULL OR m.deleted_at IS NULL)";', '" WHERE TRUE";'),
 'M7-hosted-lineage': (J+'admin/MarketplaceRegistrationService.java', 'RefTransitions.delete(repository, Marketplace.LINEAGE_REF);', 'repository.getClass();'),
 'M8-ledger-id-resolution': (J+'persistence/FetchLogRepository.java', '" COALESCE(:marketplaceId, (SELECT m.id FROM marketplaces m WHERE m.name = :marketplace"\n                        + " ORDER BY (m.deleted_at IS NULL) DESC, m.id DESC LIMIT 1)),"', '" COALESCE(:marketplaceId, NULL),"'),
 'M9-removal-skips-revocation': (J+'admin/MarketplaceRemovalService.java', 'for (Snapshot snapshot : snapshotRepository.approvedByMarketplace(marketplace.id())) {', 'for (Snapshot snapshot : List.<Snapshot>of()) {'),
 'M10-held-content-order': (J+'persistence/SnapshotRepository.java', '''(m.deleted_at IS NULL AND s.state = 'approved') DESC,"''', '''"'''),
 'M11-push-scopes-not-cut': (J+'admin/MarketplaceRemovalService.java', 'List<Long> unscoped = tokenRepository.removePushScope(marketplace.name());', 'List<Long> unscoped = List.of();'),
 'M12-push-scopes-cut-whole': (J+'persistence/TokenRepository.java', "SET push_scopes = NULLIF(array_remove(push_scopes, :name), '{}')", "SET push_scopes = NULL"),
 'M13-toggle-listing-unfiltered': (J+'vetting/VetterToggleRepository.java', 'vetter_toggles"\n                        + " WHERE marketplace_id IS NULL OR marketplace_id IN (SELECT id FROM marketplaces WHERE deleted_at IS NULL)', 'vetter_toggles'),
 'M14-chain-mode-listing-unfiltered': (J+'vetting/VettingChainSettingsRepository.java', 'vetting_chain_modes"\n                        + " WHERE marketplace_id IS NULL OR marketplace_id IN (SELECT id FROM marketplaces WHERE deleted_at IS NULL)', 'vetting_chain_modes'),
 'M15-chain-order-listing-unfiltered': (J+'vetting/VettingChainSettingsRepository.java', 'vetting_chain_orders"\n                        + " WHERE marketplace_id IS NULL OR marketplace_id IN (SELECT id FROM marketplaces WHERE deleted_at IS NULL)', 'vetting_chain_orders'),
}
for key in sys.argv[1:]:
    path, old, new = MUTANTS[key]
    f = ROOT/path
    src = f.read_text()
    if src.count(old) != 1:
        print(f'{key}: ABORT mutation site found {src.count(old)} times'); sys.exit(2)
    f.write_text(src.replace(old, new))
    try:
        r = subprocess.run(['timeout','280','./mvnw','-q','-Dskip.ui.verify=true','-Dtest=MarketplaceRemovalTests','-Dsurefire.failIfNoSpecifiedTests=false','test'], cwd=ROOT, capture_output=True, text=True)
        out = r.stdout + r.stderr
        failed = sorted(set(re.findall(r'MarketplaceRemovalTests\.([a-z_]+):\d+', out)))
        compiled = 'COMPILATION ERROR' not in out
        verdict = 'KILLED' if r.returncode != 0 and failed else ('NOT-COMPILED' if not compiled else ('ERROR rc=%d' % r.returncode if r.returncode != 0 else 'SURVIVED'))
        print(f'{key}: {verdict} {failed}', flush=True)
    finally:
        subprocess.run(['git','checkout','--',path], cwd=ROOT, check=True)
