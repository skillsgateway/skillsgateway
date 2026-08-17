package dev.skillsgateway.server.sync;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.webhook.WebhookSigner;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The inbound forge webhook (GW_0058) — the only surface reachable without an OIDC session or a
 * PAT, so its rules are strict: authentication is solely the HMAC-SHA256 signature of the exact
 * raw body against the marketplace's gateway-generated secret, and authority is nil — the payload
 * is never read, a valid signature only triggers ingestion of the registered upstream URL's
 * default branch into held quarantine, which the schedule would have done anyway.
 */
@RestController
public class InboundWebhookController {

    /** GitHub-, Gitea- and Forgejo-compatible signature header. */
    public static final String SIGNATURE_HEADER = "X-Hub-Signature-256";

    private final MarketplaceRepository marketplaceRepository;
    private final SyncService syncService;
    private final WebhookSigner signer;
    private final SkillsGatewayProperties.Sync properties;

    public InboundWebhookController(
            MarketplaceRepository marketplaceRepository,
            SyncService syncService,
            WebhookSigner signer,
            SkillsGatewayProperties properties) {
        this.marketplaceRepository = marketplaceRepository;
        this.syncService = syncService;
        this.signer = signer;
        this.properties = properties.sync();
    }

    @PostMapping("/hooks/{marketplace}")
    @Requirements({"GW_0058"})
    @Tag(name = "Sync")
    @Operation(
            summary = "Forge push webhook",
            description = "Trigger endpoint for a forge push webhook. Authenticated solely by an HMAC-SHA256"
                    + " signature of the raw request body (GitHub-compatible X-Hub-Signature-256 header)"
                    + " against the secret generated when the marketplace's sync mode was set to webhook."
                    + " The payload is ignored: a valid signature only causes the registered upstream URL's"
                    + " default branch to be ingested into a held quarantine snapshot, asynchronously.")
    @ApiResponse(responseCode = "202", description = "Trigger accepted; ingestion queued")
    @ApiResponse(responseCode = "403", description = "Missing or invalid signature")
    @ApiResponse(responseCode = "404", description = "Unknown marketplace, or its sync mode is not webhook")
    @ApiResponse(responseCode = "413", description = "Request body exceeds the configured bound")
    public ResponseEntity<Void> trigger(
            @PathVariable String marketplace,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            HttpServletRequest request)
            throws IOException {
        Marketplace registered = marketplaceRepository
                .findByName(marketplace)
                .filter(m -> Marketplace.SYNC_WEBHOOK.equals(m.syncMode()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "not found"));
        Optional<String> secret = marketplaceRepository.webhookSecret(marketplace);
        if (signature == null || secret.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "invalid signature");
        }
        byte[] body = readBounded(request, properties.maxWebhookBodyBytes());
        String expected = signer.sign(secret.get(), body);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "invalid signature");
        }
        syncService.queueWebhookIngest(registered);
        return ResponseEntity.accepted().build();
    }

    /**
     * Bounds the unauthenticated caller's work before the HMAC is computed: at most one byte past
     * the limit is ever read, and crossing the limit is a hard 413 — never a truncated-body
     * verification, which would sign different bytes than the sender did.
     */
    private static byte[] readBounded(HttpServletRequest request, long maxBytes) throws IOException {
        if (request.getContentLengthLong() > maxBytes) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "body too large");
        }
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes((int) maxBytes + 1);
            if (body.length > maxBytes) {
                throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "body too large");
            }
            return body;
        }
    }
}
