package dev.skillsgateway.server.storage;

/**
 * The set of references a marketplace serves has changed: a snapshot was published, or one was
 * withdrawn.
 *
 * <p>An event rather than a call, because of what it is allowed to cost. The publication and the
 * revocation that raise it are the gateway's two enforcement acts, and the things that want to know
 * about them — today the forge mirror (GW_0169) — are conveniences whose failures must not reach
 * either. A listener that cannot see the decision it is reacting to cannot fail it, which is what
 * makes GW_0170 a property of the wiring rather than of a reviewer's care.
 *
 * <p>It carries no snapshot and no SHA on purpose. A consumer's job is to bring some copy of the
 * marketplace into line with what is served <em>now</em>, which it reads from published storage; a
 * consumer that instead replayed a sequence of per-snapshot deltas would diverge permanently the
 * first time it missed one.
 *
 * @param marketplace the marketplace whose served references changed
 * @param reason what changed them, for the diagnostic record only
 */
public record ServedContentChangedEvent(String marketplace, String reason) {}
