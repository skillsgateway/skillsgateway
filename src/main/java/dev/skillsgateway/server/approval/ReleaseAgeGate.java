package dev.skillsgateway.server.approval;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.Snapshot;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * The cooling-off window before a snapshot can be approved (GW_0073): an operator-configured
 * minimum age, measured from the instant the gateway <em>itself</em> first ingested the snapshot's
 * commit.
 *
 * <p>The clock is deliberately the gateway's own sighting and never the commit's own timestamp. A
 * committer date is written by whoever made the commit, so an attacker who wants to defeat a
 * cooling-off window would only have to backdate one; taking the age from {@code created_at} — the
 * row the gateway wrote when it first saw that commit, and which re-ingestion of the same commit
 * leaves untouched — puts the control out of that reach.
 *
 * <p>This is not a vetting connector, on purpose. A verdict is point-in-time evidence about
 * content; "too young" is a fact about <em>now</em>. Recorded as a FAIL it would keep blocking
 * after the age had passed, until some later re-vetting run happened to overwrite it, turning a
 * wait that clears itself into an operator task. Evaluated per approval request it stores nothing,
 * needs no scheduler, and opens on its own — the same reasoning waiver expiry follows.
 */
@Component
public class ReleaseAgeGate {

    /** The setting a refusal names, so the reader knows what to change or wait for. */
    public static final String CONFIG_KEY = "skills-gateway.vetting.minimum-release-age";

    private final Duration minimum;

    public ReleaseAgeGate(SkillsGatewayProperties properties) {
        this.minimum = properties.vetting().minimumReleaseAge();
    }

    /** The configured window; {@link Duration#ZERO} when the gate is off. */
    public Duration minimum() {
        return minimum;
    }

    /**
     * Whether the snapshot may be approved now, and when it may be if not. Evaluated against the
     * current instant on every call: nothing is cached, so the answer changes on its own the moment
     * the window elapses.
     */
    @Requirements({"GW_0073"})
    public Eligibility evaluate(Snapshot snapshot) {
        return evaluate(snapshot.id(), snapshot.createdAt(), Instant.now(), minimum);
    }

    /**
     * The rule itself, as a function of its three inputs so the boundary can be verified exactly
     * rather than raced against a real clock. A snapshot is eligible when its age has
     * <em>reached</em> the minimum: at {@code firstIngestedAt + minimum} it may be approved, one
     * nanosecond earlier it may not.
     */
    static Eligibility evaluate(long snapshotId, Instant firstIngestedAt, Instant now, Duration minimum) {
        boolean off = minimum.isZero() || minimum.isNegative();
        Duration age = Duration.between(firstIngestedAt, now);
        Instant eligibleAt = off ? firstIngestedAt : firstIngestedAt.plus(minimum);
        boolean eligible = off || !now.isBefore(eligibleAt);
        Duration remaining = eligible ? Duration.ZERO : Duration.between(now, eligibleAt);
        return new Eligibility(
                snapshotId,
                eligible,
                firstIngestedAt,
                eligibleAt,
                Math.max(0, age.toSeconds()),
                remaining.toSeconds(),
                off ? 0 : minimum.toSeconds());
    }

    /**
     * The gate. Throws when the snapshot has not yet reached the configured age, before anything is
     * decided or published.
     *
     * @return the age the snapshot had when the gate let it through, for the ledger
     */
    @Requirements({"GW_0073"})
    public Duration require(Snapshot snapshot) {
        Eligibility eligibility = evaluate(snapshot);
        if (!eligibility.eligible()) {
            throw new SnapshotTooYoungException(eligibility, minimum);
        }
        return Duration.ofSeconds(eligibility.ageSeconds());
    }

    /**
     * A duration as a reviewer reads it — {@code 2d 4h}, {@code 45m}, {@code 30s}. Coarse on
     * purpose: the operative question is how much longer to wait, not the second it lands.
     */
    public static String format(Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            return "0s";
        }
        long days = duration.toDays();
        long hours = duration.toHoursPart();
        long minutes = duration.toMinutesPart();
        if (days > 0) {
            return hours > 0 ? "%dd %dh".formatted(days, hours) : "%dd".formatted(days);
        }
        if (hours > 0) {
            return minutes > 0 ? "%dh %dm".formatted(hours, minutes) : "%dh".formatted(hours);
        }
        if (minutes > 0) {
            return "%dm".formatted(minutes);
        }
        return "%ds".formatted(duration.toSecondsPart());
    }

    /**
     * Whether a snapshot has cleared the cooling-off window, and when it will if it has not.
     *
     * <p>Durations cross the wire as whole seconds beside the absolute {@code eligibleAt}, so no
     * client has to interpret a serialized duration; the portal renders the human form itself.
     */
    @Schema(description = "Whether a snapshot has cleared the minimum release age, and when it will if not")
    public record Eligibility(
            @Schema(description = "Snapshot id") long snapshotId,

            @Schema(description = "Whether the snapshot may be approved now; always true when the gate is off")
            boolean eligible,

            @Schema(
                    description = "When the gateway first ingested this commit — the age is measured from here,"
                            + " never from the commit's own timestamp")
            Instant firstIngestedAt,

            @Schema(
                    description = "The instant the snapshot becomes approvable; equal to firstIngestedAt when"
                            + " the gate is off")
            Instant eligibleAt,

            @Schema(description = "How long ago the gateway first ingested this commit, in seconds")
            long ageSeconds,

            @Schema(description = "Seconds still to wait; zero when eligible")
            long remainingSeconds,

            @Schema(description = "The configured minimum release age in seconds; zero when the gate is off")
            long minimumReleaseAgeSeconds) {}
}
