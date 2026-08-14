package io.github.jimisola.skillsgateway.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
        info =
                @Info(
                        title = "Skills Gateway",
                        description = "Enterprise gateway for git-distributed AI agent skill marketplaces:"
                                + " quarantined ingestion, held-until-approved snapshots, and a read-only"
                                + " git smart-HTTP facade serving only approved content.",
                        version = "v1",
                        contact = @Contact(name = "jimisola", url = "https://github.com/jimisola/skills-gateway"),
                        license = @License(name = "Apache-2.0", url = "https://www.apache.org/licenses/LICENSE-2.0")))
@Configuration(proxyBeanMethods = false)
public class OpenAPI {}
