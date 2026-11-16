package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.approval.AutoApprovalSettingService;
import dev.skillsgateway.server.approval.FourEyesGate;
import dev.skillsgateway.server.persistence.Marketplace;
import io.github.reqstool.annotations.SVCs;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A marketplace's automatic approval and its administrator exemption, under four-eyes {@code warn}
 * (GW_APPROVAL_0044.1, GW_APPROVAL_0044.3, GW_APPROVAL_0044.4). Every refusal is asserted on what it
 * leaves behind as well as on its status: automatic approval must still read off.
 */
class AutoApprovalSettingTests extends AbstractGatewayTest {

    private static final String INTERNAL = "https://git.internal.test/acme/";
    private static final String EXTERNAL = "https://github.com/someone-else/";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private AutoApprovalSettingService settingService;

    private static RequestPostProcessor as(String subject) {
        return oidcLogin().idToken(token -> token.subject(subject));
    }

    private Marketplace upstream(String prefix, String url, String registrant) {
        return marketplaceRepository.register(
                uniqueName(prefix), url, null, Marketplace.ORIGIN_UPSTREAM, Marketplace.PUSH_APPEND_ONLY, registrant);
    }

    private static String in(Duration duration) {
        return Instant.now().plus(duration).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private ResultActions putSetting(String name, String body, String subject) throws Exception {
        return mockMvc.perform(put("/api/v1/marketplaces/{name}/auto-approval", name)
                .with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String on(String reason, String expiresAt) {
        return """
                {"enabled": true, "reason": %s, "expiresAt": %s}""".formatted(
                reason == null ? "null" : "\"" + reason + "\"", expiresAt == null ? "null" : "\"" + expiresAt + "\"");
    }

    private static String onWithExemption(String expiresAt, String exemptionReason, String exemptionExpiresAt) {
        return """
                {"enabled": true, "reason": "vendor feed we audit", "expiresAt": "%s",
                 "exemption": {"reason": %s, "expiresAt": %s}}""".formatted(
                        expiresAt,
                        exemptionReason == null ? "null" : "\"" + exemptionReason + "\"",
                        exemptionExpiresAt == null ? "null" : "\"" + exemptionExpiresAt + "\"");
    }

    private void assertOff(String name) throws Exception {
        mockMvc.perform(get("/api/v1/marketplaces/{name}/auto-approval", name).with(as("root")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.allowedBy").value("none"));
    }

    private List<Map<String, Object>> ledger(String marketplace, String event) {
        return fetchLogRepository.list().stream()
                .filter(entry -> marketplace.equals(entry.get("marketplace")) && event.equals(entry.get("event")))
                .toList();
    }

    // --- GW_APPROVAL_0044.1 automatic approval ---

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.1"})
    void aRegisteredMarketplaceHasAutomaticApprovalOff() throws Exception {
        Marketplace marketplace = upstream("pmanual", INTERNAL + "skills.git", null);

        mockMvc.perform(get("/api/v1/marketplaces/{name}/auto-approval", marketplace.name())
                        .with(as("root")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.allowedBy").value("none"))
                .andExpect(jsonPath("$.exemption").doesNotExist());
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.1"})
    void automaticApprovalOnAnInternalMarketplaceIsSetWithItsGrantorReasonAndExpiry() throws Exception {
        Marketplace marketplace = upstream("pinternal", INTERNAL + "skills.git", null);
        String expires = in(Duration.ofDays(30));

        putSetting(marketplace.name(), on("our own platform team", expires), "root")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.allowedBy").value("internal-source"))
                .andExpect(jsonPath("$.setBy").value("root"))
                .andExpect(jsonPath("$.reason").value("our own platform team"))
                .andExpect(jsonPath("$.expiresAt").value(expires));

        assertThat(ledger(marketplace.name(), AutoApprovalSettingService.EVENT_CHANGED))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.get("principal")).isEqualTo("root");
                    assertThat(String.valueOf(entry.get("detail")))
                            .contains("enabled=true")
                            .contains("allowedBy=internal-source")
                            .contains("expires=" + expires);
                });
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.1"})
    void turningOnNeedsAReasonAndAnExpiryOfAtMostNinetyDays() throws Exception {
        Marketplace marketplace = upstream("plimits", INTERNAL + "skills.git", null);
        String name = marketplace.name();

        putSetting(name, on(null, in(Duration.ofDays(30))), "root").andExpect(status().isBadRequest());
        putSetting(name, on("  ", in(Duration.ofDays(30))), "root").andExpect(status().isBadRequest());
        putSetting(name, on("reason", null), "root").andExpect(status().isBadRequest());
        putSetting(name, on("reason", in(Duration.ofMinutes(-1))), "root").andExpect(status().isBadRequest());
        putSetting(name, on("reason", in(Duration.ofDays(91))), "root").andExpect(status().isBadRequest());
        putSetting(name, "{\"reason\": \"no enabled\"}", "root").andExpect(status().isBadRequest());
        assertOff(name);
        assertThat(ledger(name, AutoApprovalSettingService.EVENT_CHANGED)).isEmpty();

        putSetting(name, on("reason", in(Duration.ofDays(90).minusMinutes(1))), "root")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedBy").value("internal-source"));
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.1"})
    void aLapsedAutomaticApprovalIsEffectivelyOff() throws Exception {
        Marketplace marketplace = upstream("plapsed", INTERNAL + "skills.git", null);
        putSetting(marketplace.name(), on("reason", in(Duration.ofDays(30))), "root")
                .andExpect(status().isOk());

        OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).minusDays(2);
        jdbc.sql("UPDATE marketplaces SET auto_approval_set_at = :setAt, auto_approval_expires_at = :expires"
                        + " WHERE id = :id")
                .param("setAt", past)
                .param("expires", past.plusDays(1))
                .param("id", marketplace.id())
                .update();

        mockMvc.perform(get("/api/v1/marketplaces/{name}/auto-approval", marketplace.name())
                        .with(as("root")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.allowedBy").value("none"));
        assertThat(settingService.effectiveAllowedBy(
                        marketplaceRepository.findById(marketplace.id()).orElseThrow()))
                .isEqualTo(AutoApprovalSettingService.AllowedBy.NONE);
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.1"})
    void aHostedMarketplaceCanHaveAutomaticApprovalTurnedOnAtRegistration() throws Exception {
        String name = uniqueName("preghosted");
        String expires = in(Duration.ofDays(14));

        mockMvc.perform(post("/api/v1/marketplaces")
                        .with(as("root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "origin": "hosted",
                                 "autoApproval": {"enabled": true, "reason": "published by our CI", "expiresAt": "%s"}}""".formatted(name, expires)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/marketplaces/{name}/auto-approval", name).with(as("root")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.allowedBy").value("hosted"))
                .andExpect(jsonPath("$.setBy").value("root"));
        // The registrant is the grantor, so the grant conflicts; under warn it is set and recorded.
        assertThat(ledger(name, FourEyesGate.EVENT_CONFLICT))
                .singleElement()
                .satisfies(entry -> assertThat(String.valueOf(entry.get("detail")))
                        .contains(FourEyesGate.ROLE_REGISTERED_BY)
                        .contains("on=auto-approval"));
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.1"})
    void aNonAdministratorCannotSetAutomaticApprovalAndCanReadIt() throws Exception {
        Marketplace marketplace = upstream("pnonadmin", INTERNAL + "skills.git", null);

        putSetting(marketplace.name(), on("reason", in(Duration.ofDays(30))), "mallory")
                .andExpect(status().isForbidden());
        // Read by anyone who can see the marketplace's Settings, with the gateway mode, so automatic
        // approval turned on under off or dry-run does not read as publishing (acceptance R10).
        mockMvc.perform(get("/api/v1/marketplaces/{name}/auto-approval", marketplace.name())
                        .with(as("mallory")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.mode").value("off"));
        mockMvc.perform(post("/api/v1/marketplaces")
                        .with(as("mallory"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "origin": "hosted",
                                 "autoApproval": {"enabled": true, "reason": "r", "expiresAt": "%s"}}""".formatted(uniqueName("pnonadminreg"), in(Duration.ofDays(7)))))
                .andExpect(status().isForbidden());
        assertOff(marketplace.name());
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.1"})
    void turningOnAtRegistrationOutsideTheInternalSourcesIsRefusedAndNothingIsCreated() throws Exception {
        String name = uniqueName("pregext");
        String upstream = createUpstream(DEFAULT_MANIFEST).toAbsolutePath().toString();

        mockMvc.perform(post("/api/v1/marketplaces")
                        .with(as("root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "url": "%s",
                                 "autoApproval": {"enabled": true, "reason": "r", "expiresAt": "%s"}}""".formatted(name, upstream, in(Duration.ofDays(7)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.nextStep").value(org.hamcrest.Matchers.containsString("exemption")));

        assertThat(marketplaceRepository.findByName(name)).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.1"})
    void anUnknownMarketplaceIsNotFound() throws Exception {
        putSetting("no-such-marketplace", on("reason", in(Duration.ofDays(30))), "root")
                .andExpect(status().isNotFound());
    }

    // --- GW_APPROVAL_0044.3 administrator exemption ---

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.3"})
    void turningOnOutsideThePrefixesNeedsAnExemption() throws Exception {
        Marketplace marketplace = upstream("pext", EXTERNAL + "skills.git", null);

        putSetting(marketplace.name(), on("reason", in(Duration.ofDays(30))), "root")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.nextStep").value(org.hamcrest.Matchers.containsString("exemption")));
        assertOff(marketplace.name());
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.3"})
    void anExemptionNeedsAReasonAndAnExpiryOfAtMostNinetyDays() throws Exception {
        Marketplace marketplace = upstream("povlimits", EXTERNAL + "skills.git", null);
        String name = marketplace.name();
        String expires = in(Duration.ofDays(30));

        putSetting(name, onWithExemption(expires, null, in(Duration.ofDays(30))), "root")
                .andExpect(status().isBadRequest());
        putSetting(name, onWithExemption(expires, "vendor", null), "root").andExpect(status().isBadRequest());
        putSetting(name, onWithExemption(expires, "vendor", in(Duration.ofDays(91))), "root")
                .andExpect(status().isBadRequest());
        assertOff(name);
        assertThat(ledger(name, AutoApprovalSettingService.EVENT_EXEMPTION_GRANTED))
                .isEmpty();

        putSetting(name, onWithExemption(expires, "vendor feed we audit", in(Duration.ofDays(60))), "root")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedBy").value("exemption"))
                .andExpect(jsonPath("$.exemption.reason").value("vendor feed we audit"))
                .andExpect(jsonPath("$.exemption.createdBy").value("root"));
        assertThat(ledger(name, AutoApprovalSettingService.EVENT_EXEMPTION_GRANTED))
                .hasSize(1);
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.3"})
    void anExemptionIsRefusedForAMarketplaceThatIsAlreadyInternal() throws Exception {
        Marketplace marketplace = upstream("povinternal", INTERNAL + "skills.git", null);

        putSetting(
                        marketplace.name(),
                        onWithExemption(in(Duration.ofDays(30)), "unneeded", in(Duration.ofDays(30))),
                        "root")
                .andExpect(status().isBadRequest());
        assertOff(marketplace.name());
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.3"})
    void theExemptionCapsTheAutomaticApprovalsExpiry() throws Exception {
        Marketplace marketplace = upstream("povcap", EXTERNAL + "skills.git", null);
        String exemptionExpires = in(Duration.ofDays(10));

        putSetting(marketplace.name(), onWithExemption(in(Duration.ofDays(80)), "vendor", exemptionExpires), "root")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").value(exemptionExpires))
                .andExpect(jsonPath("$.exemption.expiresAt").value(exemptionExpires));

        // A renewal that leans on the standing exemption cannot outlive it either.
        putSetting(marketplace.name(), on("renewed", in(Duration.ofDays(80))), "root")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedBy").value("exemption"))
                .andExpect(jsonPath("$.expiresAt").value(exemptionExpires));
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.3"})
    void aRevokedExemptionMakesAllowedByNoneRecordedOnce() throws Exception {
        Marketplace marketplace = upstream("povrevoke", EXTERNAL + "skills.git", null);
        putSetting(
                        marketplace.name(),
                        onWithExemption(in(Duration.ofDays(30)), "vendor", in(Duration.ofDays(30))),
                        "root")
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/marketplaces/{name}/auto-approval/exemption", marketplace.name())
                        .with(as("root")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedBy").value("none"))
                .andExpect(jsonPath("$.exemption").doesNotExist());
        mockMvc.perform(delete("/api/v1/marketplaces/{name}/auto-approval/exemption", marketplace.name())
                        .with(as("root")))
                .andExpect(status().isNotFound());

        assertThat(ledger(marketplace.name(), AutoApprovalSettingService.EVENT_EXEMPTION_REVOKED))
                .singleElement()
                .satisfies(entry -> assertThat(entry.get("principal")).isEqualTo("root"));
        assertThat(settingService.effectiveAllowedBy(
                        marketplaceRepository.findById(marketplace.id()).orElseThrow()))
                .isEqualTo(AutoApprovalSettingService.AllowedBy.NONE);
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.3"})
    void anExpiredExemptionMakesAllowedByNoneRecordedOnceAcrossRepeatedAttempts() throws Exception {
        Marketplace marketplace = upstream("povexpire", EXTERNAL + "skills.git", null);
        putSetting(
                        marketplace.name(),
                        onWithExemption(in(Duration.ofDays(30)), "vendor", in(Duration.ofDays(30))),
                        "root")
                .andExpect(status().isOk());
        OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).minusDays(3);
        jdbc.sql("UPDATE auto_approval_exemptions SET created_at = :createdAt, expires_at = :expires"
                        + " WHERE marketplace_id = :id")
                .param("createdAt", past)
                .param("expires", past.plusDays(1))
                .param("id", marketplace.id())
                .update();

        // Before any attempt has recorded the lapse, a read already treats the exemption as gone.
        Marketplace current = marketplaceRepository.findById(marketplace.id()).orElseThrow();
        assertThat(settingService.effectiveAllowedBy(current)).isEqualTo(AutoApprovalSettingService.AllowedBy.NONE);
        mockMvc.perform(get("/api/v1/marketplaces/{name}/auto-approval", marketplace.name())
                        .with(as("root")))
                .andExpect(jsonPath("$.allowedBy").value("none"))
                .andExpect(jsonPath("$.exemption").doesNotExist());
        assertThat(ledger(marketplace.name(), AutoApprovalSettingService.EVENT_EXEMPTION_EXPIRED))
                .isEmpty();

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(settingService.resolveForAttempt(current)).isEqualTo(AutoApprovalSettingService.AllowedBy.NONE);
        }

        assertThat(ledger(marketplace.name(), AutoApprovalSettingService.EVENT_EXEMPTION_EXPIRED))
                .singleElement()
                .satisfies(
                        entry -> assertThat(String.valueOf(entry.get("detail"))).contains("exemption="));
        mockMvc.perform(get("/api/v1/marketplaces/{name}/auto-approval", marketplace.name())
                        .with(as("root")))
                .andExpect(jsonPath("$.allowedBy").value("none"))
                .andExpect(jsonPath("$.exemption").doesNotExist());
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.3"})
    void turningOffRevokesTheExemption() throws Exception {
        Marketplace marketplace = upstream("povmanual", EXTERNAL + "skills.git", null);
        putSetting(
                        marketplace.name(),
                        onWithExemption(in(Duration.ofDays(30)), "vendor", in(Duration.ofDays(30))),
                        "root")
                .andExpect(status().isOk());

        putSetting(marketplace.name(), "{\"enabled\": false}", "root")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.exemption").doesNotExist());

        // Turning it on again needs a fresh exemption: the old one did not survive.
        putSetting(marketplace.name(), on("again", in(Duration.ofDays(30))), "root")
                .andExpect(status().isConflict());
        assertThat(ledger(marketplace.name(), AutoApprovalSettingService.EVENT_EXEMPTION_REVOKED))
                .hasSize(1);
    }

    // --- GW_APPROVAL_0044.4 four-eyes on the grant, warn ---

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.4"})
    void underWarnAGrantByTheRegistrantIsSetAndRecordedAsAConflict() throws Exception {
        Marketplace marketplace = upstream("pfewarn", INTERNAL + "skills.git", "alice");

        putSetting(marketplace.name(), on("reason", in(Duration.ofDays(30))), "alice")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedBy").value("internal-source"));

        assertThat(ledger(marketplace.name(), FourEyesGate.EVENT_CONFLICT))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.get("principal")).isEqualTo("alice");
                    assertThat(String.valueOf(entry.get("detail")))
                            .contains(FourEyesGate.ROLE_REGISTERED_BY)
                            .contains("on=auto-approval")
                            .contains("mode=WARN");
                });
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.4"})
    void aGrantByAnotherAdministratorRaisesNoConflict() throws Exception {
        Marketplace marketplace = upstream("pfeother", INTERNAL + "skills.git", "alice");

        putSetting(marketplace.name(), on("reason", in(Duration.ofDays(30))), "root")
                .andExpect(status().isOk());

        assertThat(ledger(marketplace.name(), FourEyesGate.EVENT_CONFLICT)).isEmpty();
    }
}
