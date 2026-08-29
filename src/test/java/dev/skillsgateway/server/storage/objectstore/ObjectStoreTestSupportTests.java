package dev.skillsgateway.server.storage.objectstore;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the guard: proves the object-store suites talk to the Floci container and not to whatever
 * credentials happen to be in the developer's environment.
 *
 * <p>This is not a formality. Wired one auto-configuration short, the client this support class
 * hands out silently reached <em>real AWS</em> and failed with "the provided token has expired" —
 * a suite that had been a little luckier would have passed against someone's actual account.
 */
class ObjectStoreTestSupportTests {

    @Test
    @DisplayName("the dev service, not the developer's AWS account, is what the suites talk to")
    void theDevServiceHandsOverAUsableStore() {
        String bucket = ObjectStoreTestSupport.bucket("support-guard");

        assertThat(ObjectStoreTestSupport.s3().listBuckets().buckets().stream().map(b -> b.name()))
                .as("a bucket created here must be visible here, which it would not be against a real account")
                .contains(bucket);
    }
}
