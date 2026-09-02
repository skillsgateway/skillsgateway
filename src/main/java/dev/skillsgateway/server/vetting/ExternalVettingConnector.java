package dev.skillsgateway.server.vetting;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * An operator-configured {@link VettingConnector} that delegates the verdict to an external HTTP
 * service — an LLM reviewer, a sandbox, a corporate scanner (GW_0144, GW_0145, GW_0146, GW_0147).
 *
 * <p>The gateway POSTs the snapshot bundle ({@link ExternalVetRequest}) and reads back the
 * normalized {@link ExternalVetResponse}. It never trusts the network: <b>every</b> way the call
 * can fail to produce a verdict the gateway can stand behind is turned into an
 * {@link VerdictState#ERROR} verdict, which blocks (GW_0145). That is the whole point of this class
 * and the {@code RevetVerdict} fail-closed note applied to a hostile dependency:
 *
 * <ul>
 *   <li>connection refused, DNS failure, connect timeout, read timeout, reset — error;
 *   <li>any non-2xx status — error;
 *   <li>an empty body, a body larger than {@code max-response-bytes}, or one Jackson cannot parse —
 *       error;
 *   <li>a {@code state} the gateway does not recognize, or a malformed finding — error;
 *   <li>a snapshot whose scannable content exceeds {@code max-request-bytes} — error, because a
 *       partial bundle would earn a verdict about content the connector never saw.
 * </ul>
 *
 * <p>Two further trust properties:
 *
 * <ul>
 *   <li><b>Worst-of.</b> The recorded state is the worse of the state the endpoint declared and the
 *       state its own findings imply (GW_0146): an endpoint that returns {@code pass} alongside a
 *       {@code critical} finding cannot pass content its own evidence condemns.
 *   <li><b>Async seam.</b> A {@code pending} state is recorded as {@link VerdictState#PENDING},
 *       which blocks until it is resolved (GW_0147) — never a silent pass. The inbound resolution
 *       callback is a separate capability; until it exists a {@code pending} answer simply blocks.
 * </ul>
 *
 * <p>This connector never throws from {@link #vet}: it always returns a verdict, and a broken
 * dependency returns an error verdict rather than propagating. {@code VettingService} would catch a
 * throw anyway; returning a descriptive error keeps the reason in the finding a reviewer reads.
 */
public class ExternalVettingConnector implements VettingConnector {

    private static final Logger log = LoggerFactory.getLogger(ExternalVettingConnector.class);

    /** Rank of the states this connector may resolve to, for the worst-of rule. Excludes PENDING. */
    private static final Map<VerdictState, Integer> RANK =
            Map.of(VerdictState.PASS, 0, VerdictState.WARN, 1, VerdictState.FAIL, 2);

    private final ExternalConnectorProperties props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ExternalVettingConnector(ExternalConnectorProperties props) {
        this.props = props;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build());
        factory.setReadTimeout(props.readTimeout());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String name() {
        return props.name();
    }

    @Override
    public int order() {
        return props.order();
    }

    @Override
    public String version() {
        return props.version();
    }

    @Override
    public String description() {
        return props.description();
    }

    @Override
    @Requirements({"GW_0144", "GW_0145", "GW_0146", "GW_0147"})
    public Verdict vet(SnapshotUnderVetting snapshot) {
        ExternalVetRequest request;
        try {
            request = bundle(snapshot);
        } catch (IOException e) {
            return Verdict.error(name(), "could not read snapshot content: " + e.getMessage());
        }
        if (request == null) {
            return Verdict.error(
                    name(), "snapshot scannable content exceeds max-request-bytes (" + props.maxRequestBytes() + ")");
        }
        try {
            return restClient
                    .post()
                    .uri(props.url())
                    .headers(headers -> {
                        headers.setContentType(MediaType.APPLICATION_JSON);
                        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                        String authorization = props.authorizationValue();
                        if (authorization != null) {
                            headers.set(props.tokenHeader(), authorization);
                        }
                    })
                    .body(request)
                    .exchange((req, response) -> map(response), false);
        } catch (Exception e) {
            // Connection refused, DNS failure, timeout, reset, serialization error — all block.
            log.warn("external vetting connector '{}' call to {} failed", name(), props.url(), e);
            return Verdict.error(name(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Maps one HTTP response to a verdict, fail-closed at every branch. */
    private Verdict map(ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (!status.is2xxSuccessful()) {
            return Verdict.error(name(), "endpoint returned HTTP " + status.value());
        }
        byte[] body = readBounded(response.getBody());
        if (body == null) {
            return Verdict.error(name(), "response exceeds max-response-bytes (" + props.maxResponseBytes() + ")");
        }
        if (body.length == 0) {
            return Verdict.error(name(), "endpoint returned an empty body");
        }
        ExternalVetResponse parsed;
        try {
            parsed = objectMapper.readValue(body, ExternalVetResponse.class);
        } catch (Exception e) {
            return Verdict.error(name(), "unparseable response: " + e.getMessage());
        }
        if (parsed == null) {
            return Verdict.error(name(), "endpoint returned a null response");
        }
        return toVerdict(parsed);
    }

    /** Validates a parsed response into a verdict; any invalid field is an error, never a pass. */
    private Verdict toVerdict(ExternalVetResponse parsed) {
        VerdictState declared = parseState(parsed.state());
        if (declared == null) {
            return Verdict.error(name(), "unrecognized verdict state '" + parsed.state() + "'");
        }
        List<Finding> findings;
        try {
            findings = toFindings(parsed.findings());
        } catch (IllegalArgumentException e) {
            return Verdict.error(name(), "malformed finding: " + e.getMessage());
        }
        String reportUrl = blankToNull(parsed.reportUrl());
        String summary = summary();
        if (declared == VerdictState.PENDING) {
            // Async seam: recorded as pending, which blocks until resolved. Never a pass.
            return new Verdict(VerdictState.PENDING, findings, reportUrl, summary);
        }
        // Worst-of: the endpoint cannot declare a state weaker than its own findings imply.
        VerdictState derived = Verdict.of(findings).state();
        VerdictState effective = RANK.get(declared) >= RANK.get(derived) ? declared : derived;
        return new Verdict(effective, findings, reportUrl, summary);
    }

    /**
     * What this connector examined (GW_0143): the endpoint it delegated to and the version of the
     * external rule set it declared, recorded even for a clean pass so a pass is distinguishable
     * from a connector that never ran.
     */
    private String summary() {
        return "delegated to external connector '%s' (%s) at %s".formatted(name(), version(), props.url());
    }

    /** Accepts only the states an external connector may declare; {@code error} is gateway-internal. */
    private static VerdictState parseState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        return switch (state.trim().toLowerCase(Locale.ROOT)) {
            case "pass" -> VerdictState.PASS;
            case "warn" -> VerdictState.WARN;
            case "fail" -> VerdictState.FAIL;
            case "pending" -> VerdictState.PENDING;
            default -> null;
        };
    }

    private static List<Finding> toFindings(List<ExternalVetResponse.Finding> wire) {
        if (wire == null || wire.isEmpty()) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>(wire.size());
        for (ExternalVetResponse.Finding finding : wire) {
            if (finding == null) {
                throw new IllegalArgumentException("null finding");
            }
            Severity severity;
            try {
                severity = Severity.of(finding.severity());
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IllegalArgumentException("unknown severity '" + finding.severity() + "'");
            }
            // Finding's own constructor enforces id and message; a blank one is a malformed finding.
            findings.add(new Finding(finding.id(), severity, blankToNull(finding.location()), finding.message()));
        }
        return findings;
    }

    /** Walks the snapshot into a bundle, or null when its scannable content exceeds the cap. */
    private ExternalVetRequest bundle(SnapshotUnderVetting snapshot) throws IOException {
        List<ExternalVetRequest.File> files = new ArrayList<>();
        long[] total = {0};
        boolean[] overflow = {false};
        snapshot.walk((path, content) -> {
            if (overflow[0]) {
                return;
            }
            if (content == null || content.length > props.maxFileBytes()) {
                // Oversized or size-capped by the walk: shipped as unscanned, not dropped.
                files.add(new ExternalVetRequest.File(path, null, false));
                return;
            }
            String text = ContentRules.text(content);
            if (text == null) {
                files.add(new ExternalVetRequest.File(path, null, false));
                return;
            }
            total[0] += content.length;
            if (total[0] > props.maxRequestBytes()) {
                overflow[0] = true;
                return;
            }
            files.add(new ExternalVetRequest.File(path, text, true));
        });
        if (overflow[0]) {
            return null;
        }
        return new ExternalVetRequest(snapshot.snapshotId(), snapshot.marketplace(), snapshot.sha(), files);
    }

    /** Reads up to {@code maxResponseBytes}; returns null when the stream carries more. */
    private byte[] readBounded(InputStream in) throws IOException {
        long limit = props.maxResponseBytes();
        byte[] body = in.readNBytes((int) Math.min(limit + 1, Integer.MAX_VALUE));
        if (body.length > limit) {
            return null;
        }
        return body;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
