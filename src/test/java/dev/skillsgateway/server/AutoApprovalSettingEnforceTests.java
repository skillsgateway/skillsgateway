package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.approval.AutoApprovalSettingService;
import dev.skillsgateway.server.approval.FourEyesGate;
import dev.skillsgateway.server.estate.EstateReconciler;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.Snapshot;
import io.github.reqstool.annotations.SVCs;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Four-eyes on turning automatic approval on, under {@code enforce} (GW_APPROVAL_0044.4). The property
 * sources are exactly {@code FourEyesEnforceTests}'s, so the two suites share one context.
 */
@TestPropertySource(properties = "skills-gateway.approval.four-eyes.mode=enforce")
@TestPropertySource(properties = {"skills-gateway.roles.admins=dana,rachel"})
class AutoApprovalSettingEnforceTests extends AbstractGatewayTest {

    private static final String INTERNAL = "https://git.internal.test/acme/";

    @Autowired
    private AutoApprovalSettingService settingService;

    @Autowired
    private FourEyesGate fourEyesGate;

    private static RequestPostProcessor as(String subject) {
        return oidcLogin().idToken(token -> token.subject(subject));
    }

    private static String on(Duration expiresIn) {
        return """
                {"enabled": true, "reason": "our platform team", "expiresAt": "%s"}""".formatted(Instant.now().plus(expiresIn).truncatedTo(ChronoUnit.SECONDS));
    }

    private ResultActions putSetting(String name, String subject) throws Exception {
        return mockMvc.perform(put("/api/v1/marketplaces/{name}/auto-approval", name)
                .with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content(on(Duration.ofDays(30))));
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.4"})
    void aGrantByTheRegistrantAwaitsASecondAdministrator() throws Exception {
        Marketplace marketplace = marketplaceRepository.register(
                uniqueName("peawait"),
                INTERNAL + "skills.git",
                null,
                Marketplace.ORIGIN_UPSTREAM,
                Marketplace.PUSH_APPEND_ONLY,
                "dana");

        putSetting(marketplace.name(), "dana")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.allowedBy").value("awaiting-second-administrator"))
                .andExpect(jsonPath("$.setBy").value("dana"));
        assertThat(settingService.effectiveAllowedBy(
                        marketplaceRepository.findById(marketplace.id()).orElseThrow()))
                .isEqualTo(AutoApprovalSettingService.AllowedBy.AWAITING_SECOND_ADMINISTRATOR);
        assertThat(fetchLogRepository.list())
                .filteredOn(entry -> marketplace.name().equals(entry.get("marketplace"))
                        && FourEyesGate.EVENT_CONFLICT.equals(entry.get("event")))
                .singleElement()
                .satisfies(entry -> assertThat(String.valueOf(entry.get("detail")))
                        .contains("mode=ENFORCE")
                        .contains(FourEyesGate.ROLE_REGISTERED_BY));

        // The registrant setting it again changes nothing.
        putSetting(marketplace.name(), "dana")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedBy").value("awaiting-second-administrator"));

        // A different administrator confirms it.
        putSetting(marketplace.name(), "rachel")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedBy").value("internal-source"))
                .andExpect(jsonPath("$.setBy").value("rachel"));
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.4"})
    void automaticApprovalTurnedOnAtRegistrationAwaitsASecondAdministrator() throws Exception {
        String name = uniqueName("peregister");

        mockMvc.perform(post("/api/v1/marketplaces")
                        .with(as("dana"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "origin": "hosted", "autoApproval": %s}""".formatted(name, on(Duration.ofDays(14)))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/marketplaces/{name}/auto-approval", name).with(as("rachel")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.allowedBy").value("awaiting-second-administrator"));

        putSetting(name, "rachel")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedBy").value("hosted"));
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.4"})
    void theEstateReconcilersGrantNeedsNoSecondAdministrator() {
        Marketplace marketplace = marketplaceRepository.register(
                uniqueName("pereconciler"),
                INTERNAL + "skills.git",
                null,
                Marketplace.ORIGIN_UPSTREAM,
                Marketplace.PUSH_APPEND_ONLY,
                EstateReconciler.ACTOR);

        AutoApprovalSettingService.SettingView view = settingService.set(
                marketplace.name(),
                new AutoApprovalSettingService.SettingRequest(
                        true, "declared in the estate", Instant.now().plus(Duration.ofDays(30)), null),
                EstateReconciler.ACTOR);

        assertThat(view.allowedBy()).isEqualTo(AutoApprovalSettingService.AllowedBy.INTERNAL_SOURCE);
        assertThat(fetchLogRepository.list())
                .filteredOn(entry -> marketplace.name().equals(entry.get("marketplace"))
                        && FourEyesGate.EVENT_CONFLICT.equals(entry.get("event")))
                .isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.4"})
    void approvalsFourEyesBehaviourIsUnchangedForTheReconciler() {
        Marketplace marketplace = marketplaceRepository.register(
                uniqueName("peapprove"),
                INTERNAL + "skills.git",
                null,
                Marketplace.ORIGIN_UPSTREAM,
                Marketplace.PUSH_APPEND_ONLY,
                EstateReconciler.ACTOR);
        String sha = "0".repeat(40);
        Snapshot snapshot = new Snapshot(
                1L,
                marketplace.id(),
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

        assertThat(fourEyesGate.conflicts(snapshot, marketplace, List.of(), EstateReconciler.ACTOR))
                .extracting(conflict -> conflict.role())
                .containsExactly(FourEyesGate.ROLE_REGISTERED_BY);
    }
}
