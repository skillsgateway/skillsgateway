package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.mirror.ForgeMirrorService;
import dev.skillsgateway.server.mirror.MirrorReport;
import dev.skillsgateway.server.persistence.FetchLogRepository;
import io.github.reqstool.annotations.SVCs;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The shipped default (GW_0169): no mirror configuration, therefore no outbound push at all.
 *
 * <p>Deliberately in the shared context rather than one of its own, because the shared context is
 * the one that carries no mirror settings — which is the state every existing deployment is in, and
 * the state an upgrade must not change.
 */
class ForgeMirrorDisabledTests extends AbstractGatewayTest {

    @Autowired
    private ForgeMirrorService mirror;

    @Autowired
    private FetchLogRepository ledger;

    @Test
    @SVCs({"SVC_GW_0169"})
    void a_gateway_with_no_mirror_configured_pushes_nothing_when_a_snapshot_is_approved() throws Exception {
        assertThat(mirror.enabled()).isFalse();
        String name = uniqueName("nomirror");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));

        approve(registered.snapshot().id());

        MirrorReport report = mirror.report();
        assertThat(report).isEqualTo(MirrorReport.disabled());
        assertThat(report.enabled()).isFalse();
        assertThat(report.inSync()).isFalse();
        assertThat(report.lastAttemptOutcome()).isEqualTo(MirrorReport.NONE);
        assertThat(mirrorLedgerEvents()).isEmpty();

        // The endpoint answers rather than 404s, so an operator can tell "no mirror" from "no
        // gateway support for one" — and it is still administrator-only when there is nothing to see.
        mockMvc.perform(get("/api/mirror/drift").with(oidcLogin().idToken(token -> token.subject("mallory"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/mirror/drift").with(oidcLogin().idToken(token -> token.subject("alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.marketplace").doesNotExist());
    }

    private List<String> mirrorLedgerEvents() {
        return ledger.list().stream()
                .map(entry -> String.valueOf(entry.get("event")))
                .filter(event -> event.startsWith("mirror-"))
                .toList();
    }
}
