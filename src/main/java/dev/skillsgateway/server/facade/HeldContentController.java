package dev.skillsgateway.server.facade;

import dev.skillsgateway.server.observability.GatewayMetrics;
import dev.skillsgateway.server.persistence.AccessToken;
import dev.skillsgateway.server.persistence.MarketplaceName;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The pull-side half of revocation (GW_FACADE_0031). Revoking stops the gateway serving content;
 * it does not reach the machine that cloned it last week, and in the case revocation exists for the
 * copies already distributed are the blast radius. This is what a holder asks.
 *
 * <p>It is not under {@code /git/**} because that prefix is a servlet mapping owned by
 * {@code GitServlet} and no controller is reachable beneath it, and not under {@code /api/**}
 * because a git client holds neither of the credentials that surface accepts. Its own chain in
 * {@code SecurityConfig} authenticates it with the facade's credentials instead.
 */
@RestController
public class HeldContentController {

    /** Still approved, still fetchable — whether or not a later approval superseded it. */
    public static final String APPROVED = "approved";

    /** Withdrawn. The time is reported; the reason is not (GW_FACADE_0032). */
    public static final String REVOKED = "revoked";

    /**
     * The gateway makes no statement. Never a pass: it is the answer for a commit never held, one
     * no longer recorded, one never approved, a marketplace outside the token's scope and a
     * marketplace that does not exist, and telling those apart is the disclosure GW_AUTH_0006
     * forbids.
     */
    public static final String UNKNOWN = "unknown";

    /**
     * The most pairs one request may ask about. A constant rather than a {@code skills-gateway.*}
     * leaf: no tuning knob in this project has ever been given a non-default value by any
     * deployment, and {@code ConfigSurfaceBudgetTests} is a ratchet this has no argument for
     * raising. Large enough for a fleet client holding every marketplace in a sizeable estate.
     */
    public static final int MAX_HOLDINGS = 256;

    private final SnapshotRepository snapshots;
    private final FetchAuditHook auditHook;
    private final GatewayMetrics metrics;

    public HeldContentController(SnapshotRepository snapshots, FetchAuditHook auditHook, GatewayMetrics metrics) {
        this.snapshots = snapshots;
        this.auditHook = auditHook;
        this.metrics = metrics;
    }

    /**
     * {@code POST} for a read, the shape {@code POST /api/policy/playground} already established
     * here: forty-hex commit ids as repeated query parameters exhaust a practical URL length at
     * around forty entries, and a client holding several marketplaces has more than that.
     *
     * <p>Nothing on this path is appended to the audit ledger, deliberately (GW_FACADE_0031). The
     * append-only obligation is about fetches and administrative acts; a row per client per poll
     * would multiply the growth of the table the compliance story rests on, to record that somebody
     * asked a question rather than received content. The counter below carries the same signal at
     * fixed cost.
     */
    @PostMapping("/status/v1/snapshots")
    @Tag(name = "Held content")
    @Operation(
            summary = "Ask whether content a client already holds is still approved",
            description = "Answers one state per marketplace-and-commit pair, in the order asked."
                    + " A pair the gateway makes no positive statement about is answered 'unknown',"
                    + " which is never a pass: a client that holds such content should treat it as"
                    + " withdrawn. Authenticates with the same credentials the git facade accepts,"
                    + " and answers only about marketplaces that credential could fetch.")
    @ApiResponse(responseCode = "200", description = "One state per requested pair, in the order asked")
    @ApiResponse(responseCode = "400", description = "Malformed request, or more pairs than one request may carry")
    @ApiResponse(responseCode = "401", description = "Unauthenticated")
    @Requirements({"GW_FACADE_0031", "GW_FACADE_0032"})
    public HeldContentReport status(@RequestBody HeldContentRequest request) {
        List<Holding> holdings = validated(request);
        AccessToken token = auditHook.currentToken();

        // Scope first, before anything is looked up: the order resolvePublished establishes
        // (GW_AUTH_0006), so an out-of-scope pair never reaches a query whose timing could set it
        // apart from a marketplace that does not exist.
        List<SnapshotRepository.MarketplaceSha> askable = new ArrayList<>();
        for (Holding holding : holdings) {
            if (token == null || token.permitsMarketplace(holding.marketplace())) {
                askable.add(new SnapshotRepository.MarketplaceSha(holding.marketplace(), holding.sha()));
            }
        }

        Map<Holding, SnapshotRepository.HeldContent> found = new HashMap<>();
        for (SnapshotRepository.HeldContent row : snapshots.heldContent(askable)) {
            found.put(new Holding(row.marketplace(), row.sha()), row);
        }

        List<HeldContentAnswer> answers = new ArrayList<>(holdings.size());
        for (Holding holding : holdings) {
            answers.add(answer(holding, found.get(holding)));
        }
        return new HeldContentReport(answers);
    }

    /**
     * The single place a stored state becomes something said to a client. Everything that is not
     * approved or revoked is the absence of a statement, so a state added to {@code snapshot_state}
     * later cannot leak through as a new answer.
     */
    @Requirements({"GW_FACADE_0031", "GW_FACADE_0032"})
    private HeldContentAnswer answer(Holding holding, SnapshotRepository.HeldContent row) {
        HeldContentAnswer answer;
        if (row == null) {
            answer = new HeldContentAnswer(holding.marketplace(), holding.sha(), UNKNOWN, null, false);
        } else if (row.approved()) {
            answer = new HeldContentAnswer(
                    holding.marketplace(), holding.sha(), APPROVED, null, row.approvedOverReversedRevocation());
        } else if (row.revoked()) {
            // The time, never the reason: a revocation reason is mandatory and routinely names an
            // undisclosed vulnerability or a compromised maintainer (GW_FACADE_0032). An identity
            // entitled to it reads it from the ledger.
            answer = new HeldContentAnswer(holding.marketplace(), holding.sha(), REVOKED, row.revokedAt(), false);
        } else {
            answer = new HeldContentAnswer(holding.marketplace(), holding.sha(), UNKNOWN, null, false);
        }
        metrics.heldContentAnswer(answer.state());
        return answer;
    }

    /**
     * Refuses the request whole rather than answering the pairs it could, because a partial answer
     * that looks complete is the failure a holder cannot detect. The refusals name neither the
     * offending pair nor which of its two fields was wrong — both shapes are public, but saying
     * which one failed turns the endpoint into a probe for names.
     */
    @Requirements({"GW_FACADE_0031"})
    private static List<Holding> validated(HeldContentRequest request) {
        List<HeldContentQuery> asked = request == null || request.holdings() == null ? List.of() : request.holdings();
        if (asked.size() > MAX_HOLDINGS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "At most " + MAX_HOLDINGS + " holdings may be asked about at once");
        }
        List<Holding> holdings = new ArrayList<>(asked.size());
        for (HeldContentQuery query : asked) {
            if (query == null
                    || query.marketplace() == null
                    || query.sha() == null
                    || !MarketplaceName.isValid(query.marketplace())
                    || !ObjectId.isId(query.sha())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed holding");
            }
            holdings.add(new Holding(query.marketplace(), query.sha()));
        }
        return holdings;
    }

    /** A validated pair, and the key the answers are matched back on. */
    private record Holding(String marketplace, String sha) {}

    @Schema(description = "The content a client holds and wants a statement about")
    public record HeldContentRequest(
            @Schema(description = "Marketplace-and-commit pairs the client holds, at most " + MAX_HOLDINGS)
            List<HeldContentQuery> holdings) {}

    @Schema(description = "One marketplace-and-commit pair a client holds")
    public record HeldContentQuery(
            @Schema(description = "Marketplace name, as it appears in the fetch URL")
            String marketplace,

            @Schema(description = "The commit the client holds")
            String sha) {}

    @Schema(description = "One state per requested pair, in the order asked")
    public record HeldContentReport(
            @Schema(description = "The answers") List<HeldContentAnswer> results) {}

    @Schema(description = "What the gateway will say about one pair a client holds")
    public record HeldContentAnswer(
            @Schema(description = "Marketplace name as asked")
            String marketplace,

            @Schema(description = "Commit as asked") String sha,

            @Schema(
                    description = "approved, revoked, or unknown. 'unknown' is not a pass: the gateway makes"
                            + " no positive statement, and a holder should treat the content as withdrawn.",
                    allowableValues = {APPROVED, REVOKED, UNKNOWN})
            String state,

            @Schema(description = "When the content was withdrawn; null unless the state is 'revoked'")
            Instant revokedAt,

            @Schema(
                    description = "True when this approval stands over an administrative revocation somebody"
                            + " reversed, so content served over a reversed withdrawal is never"
                            + " indistinguishable from content nobody ever withdrew")
            boolean approvedOverReversedRevocation) {}
}
