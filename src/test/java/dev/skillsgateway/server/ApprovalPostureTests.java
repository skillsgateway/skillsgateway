package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.approval.ApprovalPostureService;
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
 * A marketplace's approval posture and its override, under four-eyes {@code warn}
 * (GW_APPROVAL_0044.1, GW_APPROVAL_0044.3, GW_APPROVAL_0044.4). Every refusal is asserted on what it
 * leaves behind as well as on its status: the posture must still read {@code manual}.
 */
class ApprovalPostureTests extends AbstractGatewayTest {

    private static final String INTERNAL = "https://git.internal.test/acme/";
    private static final String EXTERNAL = "https://github.com/someone-else/";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ApprovalPostureService postureService;

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

    private ResultActions putPosture(String name, String body, String subject) throws Exception {
        return mockMvc.perform(put("/api/v1/marketplaces/{name}/approval-posture", name)
                .with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String auto(String reason, String expiresAt) {
        return """
                {"posture": "auto", "reason": %s, "expiresAt": %s}""".formatted(
                reason == null ? "null" : "\"" + reason + "\"", expiresAt == null ? "null" : "\"" + expiresAt + "\"");
    }

    private static String autoWithOverride(String expiresAt, String overrideReason, String overrideExpiresAt) {
        return """
                {"posture": "auto", "reason": "vendor feed we audit", "expiresAt": "%s",
                 "override": {"reason": %s, "expiresAt": %s}}""".formatted(
                        expiresAt,
                        overrideReason == null ? "null" : "\"" + overrideReason + "\"",
                        overrideExpiresAt == null ? "null" : "\"" + overrideExpiresAt + "\"");
    }

    private void assertManual(String name) throws Exception {
        mockMvc.perform(get("/api/v1/marketplaces/{name}/approval-posture", name)
                        .with(as("root")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posture").value("manual"))
                .andExpect(jsonPath("$.basis").value("none"));
    }

    private List<Map<String, Object>> ledger(String marketplace, String event) {
        return fetchLogRepository.list().stream()
                .filter(entry -> marketplace.equals(entry.get("marketplace")) && event.equals(entry.get("event")))
                .toList();
    }

    // --- GW_APPROVAL_0044.1 posture ---

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.1"})
    void aRegisteredMarketplaceIsManual() throws Exception {
        Marketplace marketplace = upstream("pmanual", INTERNAL + "skills.git", null);

        mockMvc.perform(get("/api/v1/marketplaces/{name}/approval-posture", marketplace.name())
                        .with(as("root")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posture").value("manual"))
                .andExpect(jsonPath("$.basis").value("none"))
                .andExpect(jsonPath("$.override").doesNotExist());
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.1"})
    void autoOnAnInternalMarketplaceIsSetWithItsGrantorReasonAndExpiry() throws Exception {
        Marketplace marketplace = upstream("pinternal", INTERNAL + "skills.git", null);
        String expires = in(Duration.ofDays(30));

        putPosture(marketplace.name(), auto("our own platform team", expires), "root")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posture").value("auto"))
                .andExpect(jsonPath("$.basis").value("internal-prefix"))
                .andExpect(jsonPath("$.setBy").value("root"))
                .andExpect(jsonPath("$.reason").value("our own platform team"))
                .andExpect(jsonPath("$.expiresAt").value(expires));

        assertThat(ledger(marketplace.name(), ApprovalPostureService.EVENT_POSTURE_CHANGED))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.get("principal")).isEqualTo("root");
                    assertThat(String.valueOf(entry.get("detail")))
                            .contains("posture=auto")
                            .contains("basis=internal-prefix")
                            .contains("expires=" + expires);
                });
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.1"})
    void autoNeedsAReasonAndAnExpiryOfAtMostNinetyDays() throws Exception {
        Marketplace marketplace = upstream("plimits", INTERNAL + "skills.git", null);
        String name = marketplace.name();

        putPosture(name, auto(null, in(Duration.ofDays(30))), "root").andExpect(status().isBadRequest());
        putPosture(name, auto("  ", in(Duration.ofDays(30))), "root").andExpect(status().isBadRequest());
        putPosture(name, auto("reason", null), "root").andExpect(status().isBadRequest());
        putPosture(name, auto("reason", in(Duration.ofMinutes(-1))), "root").andExpect(status().isBadRequest());
        putPosture(name, auto("reason", in(Duration.ofDays(91))), "root").andExpect(status().isBadRequest());
        putPosture(name, "{\"reason\": \"no posture\"}", "root").andExpect(status().isBadRequest());
        assertManual(name);
        assertThat(ledger(name, ApprovalPostureService.EVENT_POSTURE_CHANGED)).isEmpty();

        putPosture(name, auto("reason", in(Duration.ofDays(90).minusMinutes(1))), "root")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("internal-prefix"));
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.1"})
    void aLapsedAutoPostureIsEffectivelyManual() throws Exception {
        Marketplace marketplace = upstream("plapsed", INTERNAL + "skills.git", null);
        putPosture(marketplace.name(), auto("reason", in(Duration.ofDays(30))), "root")
                .andExpect(status().isOk());

        OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).minusDays(2);
        jdbc.sql("UPDATE marketplaces SET approval_set_at = :setAt, approval_expires_at = :expires WHERE id = :id")
                .param("setAt", past)
                .param("expires", past.plusDays(1))
                .param("id", marketplace.id())
                .update();

        mockMvc.perform(get("/api/v1/marketplaces/{name}/approval-posture", marketplace.name())
                        .with(as("root")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posture").value("auto"))
                .andExpect(jsonPath("$.basis").value("none"));
        assertThat(postureService.effectiveBasis(
                        marketplaceRepository.findById(marketplace.id()).orElseThrow()))
                .isEqualTo(ApprovalPostureService.Basis.NONE);
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.1"})
    void aHostedMarketplaceCanBeChosenAutoAtRegistration() throws Exception {
        String name = uniqueName("preghosted");
        String expires = in(Duration.ofDays(14));

        mockMvc.perform(post("/api/v1/marketplaces")
                        .with(as("root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "origin": "hosted",
                                 "approval": {"posture": "auto", "reason": "published by our CI", "expiresAt": "%s"}}""".formatted(name, expires)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/marketplaces/{name}/approval-posture", name)
                        .with(as("root")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posture").value("auto"))
                .andExpect(jsonPath("$.basis").value("hosted"))
                .andExpect(jsonPath("$.setBy").value("root"));
        // The registrant is the grantor, so the grant conflicts; under warn it is set and recorded.
        assertThat(ledger(name, FourEyesGate.EVENT_CONFLICT))
                .singleElement()
                .satisfies(entry -> assertThat(String.valueOf(entry.get("detail")))
                        .contains(FourEyesGate.ROLE_REGISTERED_BY)
                        .contains("approval-posture"));
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.1"})
    void aNonAdministratorCannotSetAPostureAndCanReadIt() throws Exception {
        Marketplace marketplace = upstream("pnonadmin", INTERNAL + "skills.git", null);

        putPosture(marketplace.name(), auto("reason", in(Duration.ofDays(30))), "mallory")
                .andExpect(status().isForbidden());
        // Read by anyone who can see the marketplace's Settings, with the deployment mode, so a
        // posture set under off or shadow does not read as publishing (acceptance R10).
        mockMvc.perform(get("/api/v1/marketplaces/{name}/approval-posture", marketplace.name())
                        .with(as("mallory")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posture").value("manual"))
                .andExpect(jsonPath("$.mode").value("off"));
        mockMvc.perform(post("/api/v1/marketplaces")
                        .with(as("mallory"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "origin": "hosted",
                                 "approval": {"posture": "auto", "reason": "r", "expiresAt": "%s"}}""".formatted(uniqueName("pnonadminreg"), in(Duration.ofDays(7)))))
                .andExpect(status().isForbidden());
        assertManual(marketplace.name());
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.1"})
    void autoAtRegistrationWithoutAnInternalBasisIsRefusedAndNothingIsCreated() throws Exception {
        String name = uniqueName("pregext");
        String upstream = createUpstream(DEFAULT_MANIFEST).toAbsolutePath().toString();

        mockMvc.perform(post("/api/v1/marketplaces")
                        .with(as("root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "url": "%s",
                                 "approval": {"posture": "auto", "reason": "r", "expiresAt": "%s"}}""".formatted(name, upstream, in(Duration.ofDays(7)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.nextStep").value(org.hamcrest.Matchers.containsString("override")));

        assertThat(marketplaceRepository.findByName(name)).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.1"})
    void anUnknownMarketplaceIsNotFound() throws Exception {
        putPosture("no-such-marketplace", auto("reason", in(Duration.ofDays(30))), "root")
                .andExpect(status().isNotFound());
    }

    // --- GW_APPROVAL_0044.3 override ---

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.3"})
    void autoOutsideThePrefixesNeedsAnOverride() throws Exception {
        Marketplace marketplace = upstream("pext", EXTERNAL + "skills.git", null);

        putPosture(marketplace.name(), auto("reason", in(Duration.ofDays(30))), "root")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.nextStep").value(org.hamcrest.Matchers.containsString("override")));
        assertManual(marketplace.name());
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.3"})
    void anOverrideNeedsAReasonAndAnExpiryOfAtMostNinetyDays() throws Exception {
        Marketplace marketplace = upstream("povlimits", EXTERNAL + "skills.git", null);
        String name = marketplace.name();
        String posture = in(Duration.ofDays(30));

        putPosture(name, autoWithOverride(posture, null, in(Duration.ofDays(30))), "root")
                .andExpect(status().isBadRequest());
        putPosture(name, autoWithOverride(posture, "vendor", null), "root").andExpect(status().isBadRequest());
        putPosture(name, autoWithOverride(posture, "vendor", in(Duration.ofDays(91))), "root")
                .andExpect(status().isBadRequest());
        assertManual(name);
        assertThat(ledger(name, ApprovalPostureService.EVENT_OVERRIDE_CREATED)).isEmpty();

        putPosture(name, autoWithOverride(posture, "vendor feed we audit", in(Duration.ofDays(60))), "root")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("override"))
                .andExpect(jsonPath("$.override.reason").value("vendor feed we audit"))
                .andExpect(jsonPath("$.override.createdBy").value("root"));
        assertThat(ledger(name, ApprovalPostureService.EVENT_OVERRIDE_CREATED)).hasSize(1);
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.3"})
    void anOverrideIsRefusedWhereThereIsAlreadyAnInternalBasis() throws Exception {
        Marketplace marketplace = upstream("povinternal", INTERNAL + "skills.git", null);

        putPosture(
                        marketplace.name(),
                        autoWithOverride(in(Duration.ofDays(30)), "unneeded", in(Duration.ofDays(30))),
                        "root")
                .andExpect(status().isBadRequest());
        assertManual(marketplace.name());
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.3"})
    void theOverrideCapsThePosturesExpiry() throws Exception {
        Marketplace marketplace = upstream("povcap", EXTERNAL + "skills.git", null);
        String overrideExpires = in(Duration.ofDays(10));

        putPosture(marketplace.name(), autoWithOverride(in(Duration.ofDays(80)), "vendor", overrideExpires), "root")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").value(overrideExpires))
                .andExpect(jsonPath("$.override.expiresAt").value(overrideExpires));

        // A renewal that leans on the standing override cannot outlive it either.
        putPosture(marketplace.name(), auto("renewed", in(Duration.ofDays(80))), "root")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("override"))
                .andExpect(jsonPath("$.expiresAt").value(overrideExpires));
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.3"})
    void aRevokedOverrideMakesTheBasisNoneRecordedOnce() throws Exception {
        Marketplace marketplace = upstream("povrevoke", EXTERNAL + "skills.git", null);
        putPosture(
                        marketplace.name(),
                        autoWithOverride(in(Duration.ofDays(30)), "vendor", in(Duration.ofDays(30))),
                        "root")
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/marketplaces/{name}/approval-posture/override", marketplace.name())
                        .with(as("root")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("none"))
                .andExpect(jsonPath("$.override").doesNotExist());
        mockMvc.perform(delete("/api/v1/marketplaces/{name}/approval-posture/override", marketplace.name())
                        .with(as("root")))
                .andExpect(status().isNotFound());

        assertThat(ledger(marketplace.name(), ApprovalPostureService.EVENT_OVERRIDE_REVOKED))
                .singleElement()
                .satisfies(entry -> assertThat(entry.get("principal")).isEqualTo("root"));
        assertThat(postureService.effectiveBasis(
                        marketplaceRepository.findById(marketplace.id()).orElseThrow()))
                .isEqualTo(ApprovalPostureService.Basis.NONE);
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.3"})
    void anExpiredOverrideMakesTheBasisNoneRecordedOnceAcrossRepeatedAttempts() throws Exception {
        Marketplace marketplace = upstream("povexpire", EXTERNAL + "skills.git", null);
        putPosture(
                        marketplace.name(),
                        autoWithOverride(in(Duration.ofDays(30)), "vendor", in(Duration.ofDays(30))),
                        "root")
                .andExpect(status().isOk());
        OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).minusDays(3);
        jdbc.sql("UPDATE auto_approval_overrides SET created_at = :createdAt, expires_at = :expires"
                        + " WHERE marketplace_id = :id")
                .param("createdAt", past)
                .param("expires", past.plusDays(1))
                .param("id", marketplace.id())
                .update();

        // Before any attempt has recorded the lapse, a read already treats the override as gone.
        Marketplace current = marketplaceRepository.findById(marketplace.id()).orElseThrow();
        assertThat(postureService.effectiveBasis(current)).isEqualTo(ApprovalPostureService.Basis.NONE);
        mockMvc.perform(get("/api/v1/marketplaces/{name}/approval-posture", marketplace.name())
                        .with(as("root")))
                .andExpect(jsonPath("$.basis").value("none"))
                .andExpect(jsonPath("$.override").doesNotExist());
        assertThat(ledger(marketplace.name(), ApprovalPostureService.EVENT_OVERRIDE_EXPIRED))
                .isEmpty();

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(postureService.resolveForAttempt(current)).isEqualTo(ApprovalPostureService.Basis.NONE);
        }

        assertThat(ledger(marketplace.name(), ApprovalPostureService.EVENT_OVERRIDE_EXPIRED))
                .singleElement()
                .satisfies(
                        entry -> assertThat(String.valueOf(entry.get("detail"))).contains("override="));
        mockMvc.perform(get("/api/v1/marketplaces/{name}/approval-posture", marketplace.name())
                        .with(as("root")))
                .andExpect(jsonPath("$.basis").value("none"))
                .andExpect(jsonPath("$.override").doesNotExist());
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.3"})
    void settingManualRevokesTheOverride() throws Exception {
        Marketplace marketplace = upstream("povmanual", EXTERNAL + "skills.git", null);
        putPosture(
                        marketplace.name(),
                        autoWithOverride(in(Duration.ofDays(30)), "vendor", in(Duration.ofDays(30))),
                        "root")
                .andExpect(status().isOk());

        putPosture(marketplace.name(), "{\"posture\": \"manual\"}", "root")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posture").value("manual"))
                .andExpect(jsonPath("$.override").doesNotExist());

        // Going back to auto needs a fresh override: the old one did not survive.
        putPosture(marketplace.name(), auto("again", in(Duration.ofDays(30))), "root")
                .andExpect(status().isConflict());
        assertThat(ledger(marketplace.name(), ApprovalPostureService.EVENT_OVERRIDE_REVOKED))
                .hasSize(1);
    }

    // --- GW_APPROVAL_0044.4 four-eyes on the grant, warn ---

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.4"})
    void underWarnAGrantByTheRegistrantIsSetAndRecordedAsAConflict() throws Exception {
        Marketplace marketplace = upstream("pfewarn", INTERNAL + "skills.git", "alice");

        putPosture(marketplace.name(), auto("reason", in(Duration.ofDays(30))), "alice")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("internal-prefix"));

        assertThat(ledger(marketplace.name(), FourEyesGate.EVENT_CONFLICT))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.get("principal")).isEqualTo("alice");
                    assertThat(String.valueOf(entry.get("detail")))
                            .contains(FourEyesGate.ROLE_REGISTERED_BY)
                            .contains("approval-posture")
                            .contains("mode=WARN");
                });
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.4"})
    void aGrantByAnotherAdministratorRaisesNoConflict() throws Exception {
        Marketplace marketplace = upstream("pfeother", INTERNAL + "skills.git", "alice");

        putPosture(marketplace.name(), auto("reason", in(Duration.ofDays(30))), "root")
                .andExpect(status().isOk());

        assertThat(ledger(marketplace.name(), FourEyesGate.EVENT_CONFLICT)).isEmpty();
    }
}
