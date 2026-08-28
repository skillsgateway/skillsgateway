package dev.skillsgateway.server.persistence;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What kind of actor produced a ledger entry (GW_0128), stored explicitly on the entry rather
 * than inferred from the identity it names.
 *
 * <p>The ledger already had this vocabulary before this type existed — {@code config-reconciler},
 * {@code scheduler} and {@code system} were magic strings smuggled into the {@code principal}
 * column, distinguishable only by string comparison against values that also look like ordinary
 * identities, and enforced by nothing. Adding a fourth prefix would have extended that mistake
 * and required a defensive rule refusing an identity-provider subject for how it is spelled. An
 * explicit column removes the reserved namespace entirely: a machine credential's name is just a
 * name.
 */
@Schema(description = "What kind of actor produced a ledger entry")
public enum ActorType {

    /** An interactive session; {@code principal} is the identity-provider subject. */
    HUMAN("human"),

    /** A machine API credential; {@code principal} is the credential's own principal. */
    MACHINE("machine"),

    /** The gateway acting on its own: reconciliation, the schedulers, the waiver sweep. */
    SYSTEM("system");

    private final String value;

    ActorType(String value) {
        this.value = value;
    }

    /** The value as the database type spells it. */
    public String value() {
        return value;
    }

    public static ActorType of(String value) {
        for (ActorType actorType : values()) {
            if (actorType.value.equals(value)) {
                return actorType;
            }
        }
        throw new IllegalArgumentException("unknown actor type '%s'".formatted(value));
    }
}
