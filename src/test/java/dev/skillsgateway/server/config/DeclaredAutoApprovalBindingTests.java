package dev.skillsgateway.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.config.SkillsGatewayProperties.DeclaredMarketplace;
import dev.skillsgateway.server.config.SkillsGatewayProperties.Estate;
import io.github.reqstool.annotations.SVCs;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Declared automatic approval binds from the property names the docs give (GW_ESTATE_0006). */
class DeclaredAutoApprovalBindingTests {

    private static Estate bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("skills-gateway.estate", Estate.class)
                .get();
    }

    @Test
    @SVCs({"SVC_GW_ESTATE_0006"})
    void declaredAutomaticApprovalAndAnAttemptedExemptionBind() {
        Estate estate = bind(Map.of(
                "skills-gateway.estate.marketplaces[0].name", "skills",
                "skills-gateway.estate.marketplaces[0].url", "https://github.com/acme/skills.git",
                "skills-gateway.estate.marketplaces[0].auto-approval.enabled", "true",
                "skills-gateway.estate.marketplaces[0].auto-approval.reason", "our own skills repository",
                "skills-gateway.estate.marketplaces[0].auto-approval.expires-at", "2026-12-01T00:00:00Z",
                "skills-gateway.estate.marketplaces[0].auto-approval.exemption.reason", "trusted vendor",
                "skills-gateway.estate.marketplaces[1].name", "plain",
                "skills-gateway.estate.marketplaces[1].url", "https://github.com/acme/plain.git"));

        DeclaredMarketplace declared = estate.marketplaces().get(0);
        assertThat(declared.autoApproval().enabled()).isTrue();
        assertThat(declared.autoApproval().reason()).isEqualTo("our own skills repository");
        assertThat(declared.autoApproval().expiresAt()).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
        assertThat(declared.autoApproval().exemption()).containsEntry("reason", "trusted vendor");
        assertThat(estate.marketplaces().get(1).autoApproval()).isNull();
    }
}
