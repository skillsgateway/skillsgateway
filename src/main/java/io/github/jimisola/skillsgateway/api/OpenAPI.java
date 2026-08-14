package io.github.jimisola.skillsgateway.api;

import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
        info =
                @Info(
                        title = "Skills Gateway",
                        description = """
                                Enterprise gateway for git-distributed AI agent skill marketplaces \
                                (Claude Code plugins, Copilot, Cursor).

                                Upstream marketplaces are ingested into quarantine as immutable, \
                                SHA-identified snapshots, held until a reviewer approves them, and \
                                served to unmodified git clients over smart-HTTP — only approved \
                                content is ever fetchable, and every fetch lands in an append-only \
                                audit ledger.

                                Browser sessions authenticate via OIDC; git clients authenticate \
                                with personal access tokens over the standard credential-helper \
                                flow. This API is the same surface the admin portal uses.""",
                        version = "v1",
                        contact = @Contact(name = "jimisola", url = "https://github.com/jimisola/skills-gateway"),
                        license = @License(name = "Apache-2.0", url = "https://www.apache.org/licenses/LICENSE-2.0")),
        externalDocs =
                @ExternalDocumentation(
                        description = "Architecture, ADRs, and requirements (reqstool SSOT)",
                        url = "https://github.com/jimisola/skills-gateway#documentation"),
        tags = {
            @Tag(
                    name = "Marketplaces",
                    description = "Register upstream marketplaces and ingest them into held snapshots"),
            @Tag(name = "Snapshots", description = "Approve or reject held snapshots and retrieve their provenance"),
            @Tag(name = "Audit", description = "Append-only ledger of every git facade fetch"),
            @Tag(name = "Tokens", description = "Personal access tokens for git clients (hashed at rest, shown once)"),
            @Tag(name = "Session", description = "Identity of the authenticated browser session"),
        })
@Configuration(proxyBeanMethods = false)
public class OpenAPI {}
