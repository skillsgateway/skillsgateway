package io.github.jimisola.skillsgateway.config;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "skills-gateway")
public record SkillsGatewayProperties(Path dataDir, List<String> allowedUrlSchemes, Boolean devInsecureAuth) {

    public SkillsGatewayProperties {
        if (dataDir == null) {
            dataDir = Path.of("data");
        }
        if (allowedUrlSchemes == null || allowedUrlSchemes.isEmpty()) {
            allowedUrlSchemes = List.of("http", "https");
        }
        if (devInsecureAuth == null) {
            devInsecureAuth = false;
        }
    }
}
