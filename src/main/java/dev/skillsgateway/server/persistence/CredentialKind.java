package dev.skillsgateway.server.persistence;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What kind of credential authenticated a facade fetch (GW_AUTH_0041), stored on the entry rather than
 * inferred from what the entry does not say.
 *
 * <p>The inference would have been {@code token_id IS NULL}, and it does not work: that column is
 * already null on an administrative entry and on any facade entry older than per-token attribution
 * (GW_AUTH_0009). A third meaning would make the ledger unable to answer, without guessing, the one
 * question it exists to answer after a leak.
 */
@Schema(description = "What kind of credential authenticated a facade fetch")
public enum CredentialKind {

    /** A gateway-issued personal access token, over HTTP Basic. */
    PAT("pat"),

    /** A bearer token issued by the identity provider the web surface trusts (GW_AUTH_0040). */
    IDP("idp");

    private final String value;

    CredentialKind(String value) {
        this.value = value;
    }

    /** The value as the database type spells it. */
    public String value() {
        return value;
    }

    public static CredentialKind of(String value) {
        for (CredentialKind kind : values()) {
            if (kind.value.equals(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown credential kind '%s'".formatted(value));
    }
}
