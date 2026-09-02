package dev.skillsgateway.server.vetting;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * One operator-configured external vetting connector (GW_0144), bound from
 * {@code skills-gateway.vetting.external[*]}.
 *
 * <p>External connectors are deliberately <b>configuration</b>, not API-managed runtime state, for
 * the same reason the license policy is (see {@code SkillsGatewayProperties.License}): a chain run
 * must be attributable, so the identity of every connector that took part is stamped into the run
 * (GW_0049). A connector whose endpoint, position or rule-set version could be changed through the
 * API between two runs would make "the content cleared last month and blocks today" unanswerable.
 * Binding the chain from configuration keeps its identity a property of the deployment. This is why
 * the estate obligation (declarative estate, #65) does not apply: nothing here is API-mutable.
 *
 * <p>The credential is write-only in exactly the sense the declared-webhook secret is: reference an
 * environment variable ({@code ${VETTING_LLM_TOKEN}}) rather than inlining a literal, and it is
 * never logged, audited, or echoed by any API.
 *
 * @param name stable connector identity, recorded on every verdict and shown to reviewers; must be
 *     unique across the chain and must not collide with a built-in ({@code secret-scan},
 *     {@code prompt-injection}, {@code license-scan})
 * @param url endpoint the gateway POSTs the snapshot bundle to; required
 * @param order ascending chain position, in the same space as the built-ins; ties broken by name
 * @param version rule-set identity stamped into the chain identity (GW_0049); bump it when the
 *     external rules change so a changed answer about unchanged content is attributable
 * @param description reviewer-facing one-liner: what it looks for and what it cannot see
 * @param token credential sent to the external service; null sends no credential
 * @param tokenHeader header the credential is sent in; default {@code Authorization}
 * @param tokenScheme auth scheme prefix, applied only for the {@code Authorization} header; default
 *     {@code Bearer}. Blank sends the raw token value
 * @param connectTimeout how long to wait for the connection; exceeding it fails closed
 * @param readTimeout how long to wait for the response; exceeding it fails closed
 * @param maxRequestBytes cap on the snapshot bundle sent; a snapshot whose scannable content
 *     exceeds it fails closed rather than shipping partial evidence for a real verdict
 * @param maxResponseBytes cap on the response read; a larger response fails closed
 * @param maxFileBytes per-file cap on content included in the bundle; a larger file is sent as
 *     unscanned (no content) rather than dropped, so the external service knows the coverage gap
 */
public record ExternalConnectorProperties(
        String name,
        URI url,
        Integer order,
        String version,
        String description,
        String token,
        String tokenHeader,
        String tokenScheme,
        Duration connectTimeout,
        Duration readTimeout,
        Long maxRequestBytes,
        Long maxResponseBytes,
        Long maxFileBytes) {

    /** Built-in connector names an external connector may not shadow. */
    public static final Set<String> RESERVED_NAMES = Set.of("secret-scan", "prompt-injection", "license-scan");

    public ExternalConnectorProperties {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("external vetting connector: name is required");
        }
        name = name.trim();
        if (RESERVED_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "external vetting connector: '" + name + "' collides with a built-in connector name");
        }
        if (url == null) {
            throw new IllegalArgumentException("external vetting connector '" + name + "': url is required");
        }
        String scheme = url.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException(
                    "external vetting connector '" + name + "': url must be http or https, was " + url);
        }
        if (order == null) {
            order = 1000;
        }
        if (version == null || version.isBlank()) {
            version = "1";
        }
        if (description == null || description.isBlank()) {
            description = "External vetting connector at " + url + " (operator-configured).";
        }
        if (tokenHeader == null || tokenHeader.isBlank()) {
            tokenHeader = "Authorization";
        }
        if (tokenScheme == null) {
            tokenScheme = "Bearer";
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            connectTimeout = Duration.ofSeconds(5);
        }
        if (readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) {
            readTimeout = Duration.ofSeconds(30);
        }
        if (maxRequestBytes == null || maxRequestBytes <= 0) {
            maxRequestBytes = 5L * 1024 * 1024;
        }
        if (maxResponseBytes == null || maxResponseBytes <= 0) {
            maxResponseBytes = 1024L * 1024;
        }
        if (maxFileBytes == null || maxFileBytes <= 0) {
            maxFileBytes = 1024L * 1024;
        }
    }

    /** The header value for the credential, applying the scheme only for {@code Authorization}. */
    public String authorizationValue() {
        if (token == null || token.isBlank()) {
            return null;
        }
        if ("Authorization".equalsIgnoreCase(tokenHeader) && tokenScheme != null && !tokenScheme.isBlank()) {
            return tokenScheme.trim() + " " + token;
        }
        return token;
    }
}
