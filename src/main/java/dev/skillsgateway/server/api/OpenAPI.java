package dev.skillsgateway.server.api;

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

                                ## Model

                                Upstream marketplaces are ingested into quarantine as immutable, \
                                SHA-identified snapshots, held until a reviewer approves them, and \
                                served to unmodified git clients over smart-HTTP. Only approved \
                                content is ever fetchable; upstream changes never alter served \
                                content until re-ingested and re-approved (rug-pull protection). \
                                Every git fetch and every administrative action lands in an \
                                append-only audit ledger.

                                ## Quick start

                                1. `POST /api/marketplaces` — register an upstream by clone URL \
                                (http/https only; the gateway pins ingestion to the upstream \
                                default branch — a `ref` cannot be supplied).
                                2. `POST /api/marketplaces/{name}/ingest` — fetch the upstream \
                                into quarantine; the snapshot is `held` (or `rejected` on policy \
                                violation, e.g. non-local plugin sources).
                                3. `GET /api/snapshots/{id}/content` — inspect the plugins and \
                                skills the snapshot ships before deciding.
                                4. `POST /api/snapshots/{id}/approve` — publish it to the facade.
                                5. `POST /api/tokens` — issue a personal access token, then \
                                `git clone http://token:<PAT>@<gateway>/git/{marketplace}`.

                                ## Authentication

                                Browser sessions authenticate via OIDC (the app is its own BFF — \
                                tokens never reach the browser); git clients authenticate with \
                                personal access tokens over the standard credential-helper flow \
                                (Basic auth, username `token`). Tokens are stored as hashes and \
                                shown exactly once at creation.

                                This API is the same surface the admin portal uses. The gateway \
                                also exposes its own CycloneDX SBOM at `/actuator/sbom` and \
                                health at `/actuator/health`.""",
                        version = "v1",
                        contact = @Contact(name = "jimisola", url = "https://github.com/skillsgateway/skillsgateway"),
                        license = @License(name = "Apache-2.0", url = "https://www.apache.org/licenses/LICENSE-2.0")),
        externalDocs =
                @ExternalDocumentation(
                        description = "Architecture, ADRs, and requirements (reqstool SSOT)",
                        url = "https://github.com/skillsgateway/skillsgateway#documentation"),
        tags = {
            @Tag(
                    name = "Marketplaces",
                    description = "Register upstream marketplaces and ingest them into held, SHA-pinned snapshots."
                            + " Registration enforces the URL scheme allowlist and the gateway-pinned ref."),
            @Tag(
                    name = "Snapshots",
                    description = "The approval gate: approve or reject held snapshots, inspect their plugin/skill"
                            + " inventory, and retrieve provenance (what was served, from where, who approved)."),
            @Tag(
                    name = "Audit",
                    description = "Append-only ledger of every git facade fetch and every administrative action,"
                            + " each attributed to an identity."),
            @Tag(
                    name = "Tokens",
                    description = "Personal access tokens for git clients: hashed at rest, cleartext shown exactly"
                            + " once, revocable at any time."),
            @Tag(name = "Session", description = "Identity of the authenticated browser session."),
            @Tag(
                    name = "Policy",
                    description = "CEL deny rules evaluated fail-closed at approval time, and the read-only"
                            + " playground that tests an expression against a real snapshot before it is enforced."),
        })
@Configuration(proxyBeanMethods = false)
public class OpenAPI {}
