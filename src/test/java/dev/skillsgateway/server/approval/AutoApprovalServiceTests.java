package dev.skillsgateway.server.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.approval.ApprovalService.ApprovalOverride;
import dev.skillsgateway.server.approval.AutoApprovalSettingService.AllowedBy;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.config.SkillsGatewayProperties.AutoMode;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotClosureRepository;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import dev.skillsgateway.server.policy.PolicyDeniedException;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.storage.ServedTip;
import dev.skillsgateway.server.vetting.ChainStaleness;
import dev.skillsgateway.server.vetting.HeldSnapshotChanged;
import dev.skillsgateway.server.vetting.VerdictState;
import dev.skillsgateway.server.vetting.VettingChain.Outcome;
import dev.skillsgateway.server.vetting.VettingRepository.Run;
import dev.skillsgateway.server.vetting.VettingRepository.VerdictView;
import dev.skillsgateway.server.vetting.VettingService;
import dev.skillsgateway.server.vetting.WaiverEvaluation.Effect;
import dev.skillsgateway.server.vetting.WaiverService;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * What one attempt does with the answer, in each mode and against each gate's refusal
 * (GW_APPROVAL_0044.9–0044.12). The configuration the end-to-end suite cannot vary in its one
 * context is varied here; the checks themselves are {@link AutoApprovalEligibilityTests} and
 * the surface comparison's own tests.
 */
class AutoApprovalServiceTests {

    private static final long SNAPSHOT = 7L;
    private static final long MARKETPLACE = 3L;

    private final SkillsGatewayProperties properties = mock(SkillsGatewayProperties.class, RETURNS_DEEP_STUBS);
    private final MarketplaceRepository marketplaces = mock(MarketplaceRepository.class);
    private final SnapshotRepository snapshots = mock(SnapshotRepository.class);
    private final SnapshotClosureRepository closures = mock(SnapshotClosureRepository.class);
    private final AutoApprovalSettingService setting = mock(AutoApprovalSettingService.class);
    private final AutoApprovalSettingRepository settingRepository = mock(AutoApprovalSettingRepository.class);
    private final InternalSources internalSources = mock(InternalSources.class);
    private final RequiredVetters requiredVetters = mock(RequiredVetters.class);
    private final VettingService vetting = mock(VettingService.class);
    private final WaiverService waivers = mock(WaiverService.class);
    private final ServedTip servedTip = mock(ServedTip.class);
    private final GitStorage storage = mock(GitStorage.class);
    private final ApprovalService approvals = mock(ApprovalService.class);
    private final ApprovalRecorder recorder = mock(ApprovalRecorder.class);
    private final AdminAuditLogger ledger = mock(AdminAuditLogger.class);

    private AutoApprovalService service;
    private Snapshot held;
    private Marketplace marketplace;

    @BeforeEach
    void eligibleByDefault() throws IOException {
        InMemoryRepository repo = new InMemoryRepository(new DfsRepositoryDescription("q"));
        String sha = inertCommit(repo).name();
        held = new Snapshot(
                SNAPSHOT,
                MARKETPLACE,
                sha,
                sha,
                Snapshot.HELD,
                null,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        marketplace = mock(Marketplace.class);
        when(marketplace.id()).thenReturn(MARKETPLACE);
        when(marketplace.name()).thenReturn("m");
        when(marketplace.hosted()).thenReturn(true);

        when(properties.approval().auto().mode()).thenReturn(AutoMode.ON);
        when(properties.vetting().revet().enabled()).thenReturn(true);
        when(snapshots.findById(SNAPSHOT)).thenReturn(Optional.of(held));
        when(snapshots.newestLive(MARKETPLACE)).thenReturn(Optional.of(held));
        when(snapshots.recordAutoApprovalSkip(anyLong(), anyString())).thenReturn(true);
        when(marketplaces.findById(MARKETPLACE)).thenReturn(Optional.of(marketplace));
        when(setting.resolveForAttempt(marketplace)).thenReturn(AllowedBy.HOSTED);
        when(setting.effectiveAllowedBy(marketplace)).thenReturn(AllowedBy.HOSTED);
        when(setting.view("m"))
                .thenReturn(new AutoApprovalSettingService.SettingView(
                        true,
                        AllowedBy.HOSTED,
                        "root",
                        Instant.now(),
                        "r",
                        null,
                        null,
                        SkillsGatewayProperties.AutoMode.ON));
        when(closures.findBySnapshot(SNAPSHOT)).thenReturn(Optional.empty());
        when(servedTip.of("m")).thenReturn(Optional.empty());
        when(vetting.chainStaleness(held)).thenReturn(ChainStaleness.of("c", "c"));
        when(vetting.latestRun(SNAPSHOT))
                .thenReturn(Optional.of(new Run(
                        1,
                        SNAPSHOT,
                        "ingestion",
                        Outcome.CLEAR,
                        Instant.now(),
                        Instant.now(),
                        "c",
                        List.of(new VerdictView(1, "secret-scan", 0, VerdictState.PASS, "", null, List.of())))));
        when(waivers.evaluate(held))
                .thenReturn(new Effect(Outcome.CLEAR, Outcome.CLEAR, List.of(), List.of(), List.of()));
        when(requiredVetters.names()).thenReturn(List.of("secret-scan"));
        when(requiredVetters.everyVetter()).thenReturn(true);
        when(storage.quarantine("m")).thenReturn(repo);
        when(approvals.approve(eq(SNAPSHOT), eq(AutoApprovalService.ACTOR), any(ApprovalOverride.class)))
                .thenReturn(mock(ApprovalService.Approved.class));

        service = new AutoApprovalService(
                properties,
                marketplaces,
                snapshots,
                closures,
                setting,
                settingRepository,
                internalSources,
                requiredVetters,
                vetting,
                waivers,
                servedTip,
                storage,
                approvals,
                recorder,
                ledger);
    }

    private static ObjectId inertCommit(InMemoryRepository repo) throws IOException {
        DirCache index = DirCache.newInCore();
        DirCacheBuilder builder = index.builder();
        try (ObjectInserter inserter = repo.newObjectInserter()) {
            String[][] files = {
                {".claude-plugin/marketplace.json", "{\"name\": \"m\", \"plugins\": []}"}, {"README.md", "# m\n"}
            };
            for (String[] file : files) {
                DirCacheEntry entry = new DirCacheEntry(file[0]);
                entry.setFileMode(FileMode.REGULAR_FILE);
                entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, file[1].getBytes(StandardCharsets.UTF_8)));
                builder.add(entry);
            }
            builder.finish();
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(index.writeTree(inserter));
            PersonIdent ident = new PersonIdent("T", "t@example.com");
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage("fixture");
            ObjectId id = inserter.insert(commit);
            inserter.flush();
            return id;
        }
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.12"})
    void underOffNothingIsEvaluated() {
        when(properties.approval().auto().mode()).thenReturn(AutoMode.OFF);

        assertThat(service.attempt(SNAPSHOT)).isEqualTo(AutoApprovalService.Outcome.OFF);

        verifyNoInteractions(snapshots, vetting, waivers, storage, approvals, ledger);
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.12"})
    void underOnAnEligibleSnapshotIsApprovedThroughTheOneApprovalPath() {
        assertThat(service.attempt(SNAPSHOT)).isEqualTo(AutoApprovalService.Outcome.APPROVED);

        verify(approvals).approve(SNAPSHOT, AutoApprovalService.ACTOR, ApprovalOverride.none());
        verify(recorder).recordApproved(any(), eq(AutoApprovalService.ACTOR));
        verify(ledger)
                .record(
                        eq(AutoApprovalService.ACTOR),
                        eq("m"),
                        eq(AutoApprovalService.EVENT_APPROVED),
                        eq(held.sha()),
                        startsWith("allowedBy=hosted; grantor=root; chain=c; baseline=none; worst=none; waivers=[]"));
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.12", "SVC_GW_APPROVAL_0044.11"})
    void underDryRunEveryCheckRunsAndNothingIsPublished() throws IOException {
        when(properties.approval().auto().mode()).thenReturn(AutoMode.DRY_RUN);
        when(snapshots.recordAutoApprovalSkip(SNAPSHOT, AutoApprovalService.WOULD_APPROVE))
                .thenReturn(true, false);

        assertThat(service.attempt(SNAPSHOT)).isEqualTo(AutoApprovalService.Outcome.WOULD_APPROVE);
        assertThat(service.attempt(SNAPSHOT)).isEqualTo(AutoApprovalService.Outcome.WOULD_APPROVE);

        verify(approvals, never()).approve(anyLong(), anyString(), any());
        verify(storage, times(2)).quarantine("m");
        verify(ledger, times(1))
                .record(
                        eq(AutoApprovalService.ACTOR),
                        eq("m"),
                        eq(AutoApprovalService.EVENT_WOULD_APPROVE),
                        eq(held.sha()),
                        anyString());
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.8"})
    void aSurfaceThatCannotBeComparedKeepsTheSnapshotHeldUnderItsOwnReason() throws IOException {
        when(storage.quarantine("m")).thenThrow(new IllegalStateException("quarantine unavailable"));

        assertThat(service.attempt(SNAPSHOT)).isEqualTo(AutoApprovalService.Outcome.SKIPPED);

        verify(approvals, never()).approve(anyLong(), anyString(), any());
        verify(snapshots)
                .recordAutoApprovalSkip(
                        eq(SNAPSHOT),
                        startsWith("surface-unreadable: the surface could not be compared (IllegalStateException)"));
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.12"})
    void withReVettingOffTheAttemptSkips() {
        when(properties.vetting().revet().enabled()).thenReturn(false);

        assertThat(service.attempt(SNAPSHOT)).isEqualTo(AutoApprovalService.Outcome.SKIPPED);

        verify(approvals, never()).approve(anyLong(), anyString(), any());
        verify(ledger)
                .record(
                        anyString(),
                        anyString(),
                        eq(AutoApprovalService.EVENT_SKIPPED),
                        anyString(),
                        startsWith("revet-disabled"));
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.11"})
    void aRepeatedSkipIsLedgeredOnce() {
        when(setting.resolveForAttempt(marketplace)).thenReturn(AllowedBy.NONE);
        when(snapshots.recordAutoApprovalSkip(SNAPSHOT, "not-allowed: none")).thenReturn(true, false);

        service.attempt(SNAPSHOT);
        service.attempt(SNAPSHOT);

        verify(ledger, times(1))
                .record(
                        anyString(),
                        anyString(),
                        eq(AutoApprovalService.EVENT_SKIPPED),
                        anyString(),
                        eq("not-allowed: none"));
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.9"})
    void aGateRefusalLeavesItHeldWithAStableReason() {
        when(approvals.approve(eq(SNAPSHOT), eq(AutoApprovalService.ACTOR), any()))
                .thenThrow(mock(SnapshotTooYoungException.class))
                .thenThrow(mock(PolicyDeniedException.class))
                .thenThrow(mock(NameCollisionException.class))
                .thenThrow(mock(ClosureIncompleteException.class));

        for (int i = 0; i < 4; i++) {
            assertThat(service.attempt(SNAPSHOT)).isEqualTo(AutoApprovalService.Outcome.SKIPPED);
        }

        verify(recorder, never()).recordApproved(any(), any());
        verify(snapshots).recordAutoApprovalSkip(SNAPSHOT, "gate-refused: snapshot-too-young");
        verify(snapshots).recordAutoApprovalSkip(SNAPSHOT, "gate-refused: policy-denied");
        verify(snapshots).recordAutoApprovalSkip(SNAPSHOT, "gate-refused: name-collision");
        verify(snapshots).recordAutoApprovalSkip(SNAPSHOT, "gate-refused: closure-incomplete");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.9"})
    void aTooYoungSnapshotIsApprovedOnALaterTickOnceOfAge() {
        when(settingRepository.enabledMarketplaces()).thenReturn(List.of(MARKETPLACE));
        when(approvals.approve(eq(SNAPSHOT), eq(AutoApprovalService.ACTOR), any()))
                .thenThrow(mock(SnapshotTooYoungException.class))
                .thenReturn(mock(ApprovalService.Approved.class));

        service.onHeldSnapshotChanged(new HeldSnapshotChanged(MARKETPLACE, "ingested"));
        verify(recorder, never()).recordApproved(any(), any());

        service.onRevetTick(new HeldSnapshotChanged.RevetTick());
        verify(recorder).recordApproved(any(), eq(AutoApprovalService.ACTOR));
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.9"})
    void whenAPersonDecidesFirstTheAttemptRecordsASkipAndPublishesNothing() {
        when(approvals.approve(eq(SNAPSHOT), eq(AutoApprovalService.ACTOR), any()))
                .thenThrow(new IllegalStateException("invalid transition approved -> approved"));
        // The person's decision is in: the row is no longer held, so the stored reason cannot change.
        when(snapshots.recordAutoApprovalSkip(anyLong(), anyString())).thenReturn(false);

        assertThat(service.attempt(SNAPSHOT)).isEqualTo(AutoApprovalService.Outcome.SKIPPED);

        verify(recorder, never()).recordApproved(any(), any());
        verify(ledger)
                .record(
                        anyString(),
                        anyString(),
                        eq(AutoApprovalService.EVENT_SKIPPED),
                        anyString(),
                        startsWith("decided-elsewhere"));
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.11"})
    void theEvidenceIsCapturedBeforeTheApprovalItRecords() {
        assertThat(service.attempt(SNAPSHOT)).isEqualTo(AutoApprovalService.Outcome.APPROVED);

        InOrder order = inOrder(setting, approvals);
        order.verify(setting).view("m");
        order.verify(approvals).approve(eq(SNAPSHOT), eq(AutoApprovalService.ACTOR), any());
        verify(ledger)
                .record(
                        anyString(),
                        anyString(),
                        eq(AutoApprovalService.EVENT_APPROVED),
                        anyString(),
                        contains("grantor=root"));
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.9"})
    void aSettingTurnedOffDuringTheAttemptPublishesNothing() {
        when(setting.effectiveAllowedBy(marketplace)).thenReturn(AllowedBy.NONE);

        assertThat(service.attempt(SNAPSHOT)).isEqualTo(AutoApprovalService.Outcome.SKIPPED);

        verify(approvals, never()).approve(anyLong(), anyString(), any());
        verify(snapshots).recordAutoApprovalSkip(SNAPSHOT, "not-allowed: none");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.9"})
    void aFailureToPublishIsNotRecordedAsAGateRefusal() {
        when(approvals.approve(eq(SNAPSHOT), eq(AutoApprovalService.ACTOR), any()))
                .thenThrow(new ApprovalException("publish failed for snapshot 7", new IOException("disk")))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        service.attempt(SNAPSHOT);
        service.attempt(SNAPSHOT);

        verify(snapshots).recordAutoApprovalSkip(SNAPSHOT, "approval-failed: approval");
        verify(snapshots).recordAutoApprovalSkip(SNAPSHOT, "approval-failed: data-access-resource-failure");
    }
}
