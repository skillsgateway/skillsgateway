package dev.skillsgateway.server;

import dev.skillsgateway.server.persistence.Snapshot;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.test.context.TestPropertySource;

/**
 * The name-collision rule at its enforcing default (GW_APPROVAL_0019), in one context shared by its
 * suites. The shared context turns the rule off because every suite there approves the same fixture
 * plugin; here every fixture carries plugin names no other test uses, so the only collisions are the
 * ones a test arranges.
 */
@TestPropertySource(properties = "skills-gateway.approval.name-collision.enabled=true")
abstract class AbstractNameCollisionTest extends AbstractGatewayTest {

    /** A plugin name nobody else in the run uses; always contains an {@code o} for {@link #lookalike}. */
    protected static String uniquePlugin() {
        return uniqueName("tool-");
    }

    /** The same name with its Latin {@code o}s swapped for Cyrillic ones: distinct bytes, one key. */
    protected static String lookalike(String pluginName) {
        return pluginName.replace("o", "о");
    }

    /** A manifest declaring these plugins, all served from the fixture's one plugin directory. */
    protected static String manifest(String... pluginNames) {
        return """
                {
                  "name": "test-marketplace",
                  "owner": {"name": "Test"},
                  "plugins": [
                %s
                  ]
                }
                """.formatted(Arrays.stream(pluginNames)
                .map(name -> "    {\"name\": \"%s\", \"source\": \"./plugins/hello\", \"description\": \"test\"}"
                        .formatted(name))
                .collect(Collectors.joining(",\n")));
    }

    /** A fresh marketplace whose first snapshot declares these plugins, held. */
    protected Registered held(String... pluginNames) throws Exception {
        Path upstream = createUpstream(manifest(pluginNames));
        return registerAndIngest(uniqueName("nc"), upstream);
    }

    /** A fresh marketplace whose first snapshot declares these plugins, approved. */
    protected Registered approved(String... pluginNames) throws Exception {
        Registered registered = held(pluginNames);
        Snapshot approved = approve(registered.snapshot().id());
        return new Registered(registered.marketplace(), approved);
    }
}
