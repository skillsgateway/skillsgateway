package dev.skillsgateway.server.ingestion;

import dev.skillsgateway.server.config.SkillsGatewayProperties.ResolutionBudgets;
import io.github.reqstool.annotations.Requirements;
import java.time.Clock;
import java.time.Instant;

/**
 * What resolving one manifest's external plugin sources is allowed to cost (GW_0158).
 *
 * <p>One instance per ingestion: the per-source bounds are checked against each source and the
 * closure bound accumulates, so a manifest cannot get past a per-source limit by declaring twenty
 * sources just under it. Every method answers a violation string or {@code null}, the same shape as
 * every other refusal on this path, so a breached budget reaches the operator as a rejected
 * snapshot naming the number to raise rather than as a stack trace.
 *
 * <p>The deadline is taken from an injected {@link Clock} rather than {@code System.nanoTime} so
 * that crossing it is something a test can do rather than something a test has to wait for.
 */
public final class ResolutionBudget {

    private final ResolutionBudgets limits;
    private final Clock clock;
    private final Instant deadline;

    private long closureInflatedBytes;

    public ResolutionBudget(ResolutionBudgets limits, Clock clock) {
        this.limits = limits;
        this.clock = clock;
        this.deadline = clock.instant().plus(limits.deadline());
    }

    /** How much content one source contributed, measured from the objects the fetch produced. */
    public record Measurement(
            long receivedBytes, long inflatedBytes, long objects, long largestBlobBytes, int treeDepth) {}

    /**
     * The received-byte bound, published so the transfer itself can stop at it. Measuring after the
     * fact would mean the bytes the bound exists to refuse have already been received, which for an
     * endless stream is the whole failure.
     */
    public long maxReceivedBytes() {
        return limits.maxReceivedBytes().toBytes();
    }

    /** How many redirect hops one request may take. */
    public int maxRedirects() {
        return limits.maxRedirects();
    }

    /** Returns why the resolution may not continue, or {@code null} while there is time left. */
    @Requirements({"GW_0158"})
    public String expired() {
        return clock.instant().isAfter(deadline)
                ? "resolving the external plugin sources exceeded the %s deadline".formatted(limits.deadline())
                : null;
    }

    /**
     * Accounts for one source and returns why it may not be accepted, or {@code null}. The closure
     * total advances only when the source is accepted, so a refusal leaves the accumulator exactly
     * where the last accepted source left it.
     */
    @Requirements({"GW_0158"})
    public String accept(String pluginName, Measurement measurement) {
        String reason = reason(measurement);
        if (reason != null) {
            return "plugin '%s' exceeds a resolution budget: %s".formatted(pluginName, reason);
        }
        long closure = closureInflatedBytes + measurement.inflatedBytes();
        if (closure > limits.maxClosureBytes().toBytes()) {
            return "plugin '%s' exceeds a resolution budget: the manifest's sources total %d uncompressed bytes,"
                            .formatted(pluginName, closure)
                    + " over the %d permitted"
                            .formatted(limits.maxClosureBytes().toBytes());
        }
        closureInflatedBytes = closure;
        return null;
    }

    private String reason(Measurement measurement) {
        if (measurement.receivedBytes() > limits.maxReceivedBytes().toBytes()) {
            return "it received %d bytes, over the %d permitted for one source"
                    .formatted(
                            measurement.receivedBytes(),
                            limits.maxReceivedBytes().toBytes());
        }
        if (measurement.inflatedBytes() > limits.maxInflatedBytes().toBytes()) {
            return "its content is %d uncompressed bytes, over the %d permitted for one source"
                    .formatted(
                            measurement.inflatedBytes(),
                            limits.maxInflatedBytes().toBytes());
        }
        // Both byte bounds can hold while the expansion is extreme, which is exactly the pack-bomb
        // shape: a few bytes on the wire becoming everything the process can hold.
        //
        // Judged only once the content is materially large, and that floor is not a softening. An
        // unfloored ratio refuses ordinary repositories — a 128 KiB file of repeated text arrives
        // in a couple of hundred bytes, a ratio in the hundreds and a threat to nothing — while
        // below the floor the absolute inflated bound already caps what can be materialised.
        if (measurement.inflatedBytes() >= ratioFloorBytes()
                && measurement.receivedBytes() > 0
                && measurement.inflatedBytes() / measurement.receivedBytes() > limits.maxInflationRatio()) {
            return "its content expands %dx, over the %dx ratio permitted"
                    .formatted(measurement.inflatedBytes() / measurement.receivedBytes(), limits.maxInflationRatio());
        }
        if (measurement.objects() > limits.maxObjects()) {
            return "it contributes %d objects, over the %d permitted"
                    .formatted(measurement.objects(), limits.maxObjects());
        }
        if (measurement.largestBlobBytes() > limits.maxBlobBytes().toBytes()) {
            return "it contains a file of %d bytes, over the %d permitted"
                    .formatted(
                            measurement.largestBlobBytes(),
                            limits.maxBlobBytes().toBytes());
        }
        if (measurement.treeDepth() > limits.maxTreeDepth()) {
            return "its tree depth is %d, over the %d permitted"
                    .formatted(measurement.treeDepth(), limits.maxTreeDepth());
        }
        return null;
    }

    /**
     * Below how much inflated content the expansion ratio is not judged: a quarter of the
     * per-source inflated bound. Derived from that bound rather than fixed so the floor scales with
     * a deployment's own idea of "large", and so a test can reach it without allocating megabytes.
     */
    private long ratioFloorBytes() {
        return Math.max(1, limits.maxInflatedBytes().toBytes() / 4);
    }
}
