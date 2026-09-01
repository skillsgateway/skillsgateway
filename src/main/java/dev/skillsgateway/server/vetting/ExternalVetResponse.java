package dev.skillsgateway.server.vetting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The normalized answer an external vetting connector returns (GW_0142, GW_0144): the
 * {@code {verdict, report-url, findings[]}} of ARCHITECTURE.md §4, over the wire.
 *
 * <p>Unknown JSON properties are ignored so the contract can grow additively, but the fields it
 * does read are validated fail-closed by {@link ExternalVettingConnector}: a missing or
 * unrecognized {@code state}, or a malformed finding, is an error verdict that blocks — never a
 * silent pass.
 *
 * @param state one of {@code pass}, {@code warn}, {@code fail}, {@code pending} (case-insensitive);
 *     {@code error} is a gateway-internal state and is not accepted from the wire
 * @param reportUrl where a fuller external report lives, or null
 * @param findings what led to the conclusion; may be null or empty
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExternalVetResponse(String state, String reportUrl, List<Finding> findings) {

    /**
     * One finding as it arrives over the wire, before validation into a {@link
     * dev.skillsgateway.server.vetting.Finding}.
     *
     * @param id stable rule identifier
     * @param severity one of {@code info}, {@code low}, {@code medium}, {@code high}, {@code
     *     critical} (case-insensitive)
     * @param location where in the snapshot, normally {@code path:line}
     * @param message reviewer-facing explanation
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Finding(String id, String severity, String location, String message) {}
}
