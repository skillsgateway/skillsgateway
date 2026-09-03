package dev.skillsgateway.server.facade;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.SymbolicRef;
import org.junit.jupiter.api.Test;

/**
 * The want-to-ref resolution behind the fetch ledger's {@code ref} column (GW_0154), as pure logic:
 * no container, no git client, no {@code UploadPack}.
 *
 * <p>These are the cases a real-client test cannot reach or cannot distinguish. The ambiguous case
 * — main's tip and a snapshot reference on the same commit — is indistinguishable on the wire, so
 * only a direct test can pin which name the rule picks. The no-match case is unreachable through
 * the servlet under {@code RequestPolicy.ADVERTISED} and is exactly the fallback that must never
 * become a constant again.
 */
class WantedRefTests {

    private static final ObjectId TIP = ObjectId.fromString("1111111111111111111111111111111111111111");
    private static final ObjectId SUPERSEDED = ObjectId.fromString("2222222222222222222222222222222222222222");
    private static final ObjectId UNADVERTISED = ObjectId.fromString("3333333333333333333333333333333333333333");

    @Test
    @SVCs({"SVC_GW_0154"})
    void the_tip_wins_when_a_snapshot_ref_names_the_same_commit() {
        Map<String, Ref> advertised = advertised(TIP, SUPERSEDED);

        assertThat(GitFacadeConfiguration.wantedRef(advertised, TIP))
                .as("a clone and a fetch of the current snapshot by name send the same want")
                .isEqualTo("refs/heads/main");
    }

    @Test
    @SVCs({"SVC_GW_0154"})
    void a_superseded_snapshot_resolves_to_its_own_ref() {
        Map<String, Ref> advertised = advertised(TIP, SUPERSEDED);

        assertThat(GitFacadeConfiguration.wantedRef(advertised, SUPERSEDED))
                .isEqualTo("refs/snapshots/" + SUPERSEDED.name());
    }

    @Test
    @SVCs({"SVC_GW_0154"})
    void a_want_matching_no_advertised_ref_resolves_to_nothing() {
        Map<String, Ref> advertised = advertised(TIP, SUPERSEDED);

        assertThat(GitFacadeConfiguration.wantedRef(advertised, UNADVERTISED))
                .as("the ledger says it does not know rather than naming a ref nobody asked for")
                .isNull();
    }

    @Test
    @SVCs({"SVC_GW_0154"})
    void head_is_never_the_recorded_name() {
        // HEAD is advertised so a clone can learn which branch to check out, and it points at the
        // tip. The ledger names the branch, which is what a reader of it can act on.
        Map<String, Ref> advertised = advertised(TIP, SUPERSEDED);
        assertThat(advertised).containsKey(Constants.HEAD);

        assertThat(GitFacadeConfiguration.wantedRef(advertised, TIP)).isEqualTo("refs/heads/main");
    }

    @Test
    @SVCs({"SVC_GW_0154"})
    void a_marketplace_with_no_served_tip_still_resolves_a_snapshot_ref() {
        // What revocation of the tip leaves behind: the snapshot references survive without main.
        Map<String, Ref> advertised = new LinkedHashMap<>();
        advertised.put(
                "refs/snapshots/" + SUPERSEDED.name(), objectIdRef("refs/snapshots/" + SUPERSEDED.name(), SUPERSEDED));

        assertThat(GitFacadeConfiguration.wantedRef(advertised, SUPERSEDED))
                .isEqualTo("refs/snapshots/" + SUPERSEDED.name());
        assertThat(GitFacadeConfiguration.wantedRef(advertised, TIP)).isNull();
    }

    /** The advertised surface of a marketplace serving {@code tip} with one superseded snapshot. */
    private static Map<String, Ref> advertised(ObjectId tip, ObjectId superseded) {
        Map<String, Ref> refs = new LinkedHashMap<>();
        Ref main = objectIdRef("refs/heads/main", tip);
        refs.put(Constants.HEAD, new SymbolicRef(Constants.HEAD, main));
        refs.put("refs/heads/main", main);
        refs.put("refs/snapshots/" + tip.name(), objectIdRef("refs/snapshots/" + tip.name(), tip));
        refs.put("refs/snapshots/" + superseded.name(), objectIdRef("refs/snapshots/" + superseded.name(), superseded));
        return refs;
    }

    private static Ref objectIdRef(String name, ObjectId id) {
        return new ObjectIdRef.PeeledNonTag(Ref.Storage.PACKED, name, id);
    }
}
