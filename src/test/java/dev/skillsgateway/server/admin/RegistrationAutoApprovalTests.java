package dev.skillsgateway.server.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.skillsgateway.server.admin.AdminController.RegisterMarketplaceRequest;
import dev.skillsgateway.server.admin.AdminController.RegisteredMarketplace;
import dev.skillsgateway.server.admin.AdminController.RegistrationAutoApproval;
import dev.skillsgateway.server.approval.ApprovalRecorder;
import dev.skillsgateway.server.approval.ApprovalService;
import dev.skillsgateway.server.approval.AutoApprovalService;
import dev.skillsgateway.server.approval.AutoApprovalSettingService;
import dev.skillsgateway.server.approval.RevocationService;
import dev.skillsgateway.server.ingestion.IngestJobs;
import dev.skillsgateway.server.ingestion.SnapshotContentService;
import dev.skillsgateway.server.persistence.FetchLogRepository;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import dev.skillsgateway.server.roles.RoleService;
import dev.skillsgateway.server.storage.ServedTip;
import dev.skillsgateway.server.vetting.LicenseReportService;
import dev.skillsgateway.server.webhook.WebhookService;
import io.github.reqstool.annotations.SVCs;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;

/**
 * A registration that carries automatic approval writes the marketplace before the setting, and
 * the first cannot be undone by a rollback: its repository and its webhook already exist. A
 * setting that then fails must not answer as if nothing was registered, or the retry meets
 * "already exists" for a marketplace the caller was told does not.
 */
class RegistrationAutoApprovalTests {

    private final MarketplaceRegistrationService registration = mock(MarketplaceRegistrationService.class);
    private final AutoApprovalSettingService setting = mock(AutoApprovalSettingService.class);
    private final AdminController controller = new AdminController(
            mock(ServedTip.class),
            registration,
            mock(MarketplaceRemovalService.class),
            mock(MarketplaceRepository.class),
            mock(SnapshotRepository.class),
            mock(IngestJobs.class),
            mock(ApprovalService.class),
            mock(RevocationService.class),
            mock(FetchLogRepository.class),
            mock(SnapshotContentService.class),
            mock(LicenseReportService.class),
            mock(AdminAuditLogger.class),
            mock(WebhookService.class),
            mock(ApprovalRecorder.class),
            mock(RoleService.class),
            setting,
            mock(AutoApprovalService.class));

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.1"})
    void aSettingThatFailsAfterRegistrationIsReportedOnTheRegisteredMarketplace() {
        Marketplace registered = mock(Marketplace.class);
        when(registered.name()).thenReturn("acme");
        when(registration.register(eq("acme"), eq("https://forge.invalid/acme.git"), eq("root")))
                .thenReturn(new MarketplaceRegistrationService.RegistrationOutcome(registered, List.of()));
        when(setting.set(eq("acme"), any(), anyString())).thenThrow(new IllegalStateException("database unavailable"));
        Instant expires = Instant.now().plus(7, ChronoUnit.DAYS);

        ResponseEntity<RegisteredMarketplace> response = controller.registerMarketplace(
                new RegisterMarketplaceRequest(
                        "acme",
                        "https://forge.invalid/acme.git",
                        null,
                        null,
                        null,
                        new RegistrationAutoApproval(true, "our CI", expires)),
                new TestingAuthenticationToken("root", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().warnings())
                .singleElement()
                .satisfies(warning ->
                        assertThat(warning).contains("registered").contains("automatic approval was not turned on"));
    }
}
