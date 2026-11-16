package dev.skillsgateway.server.approval;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.config.SkillsGatewayProperties.Auto;
import dev.skillsgateway.server.vetting.Vetter;
import io.github.reqstool.annotations.Requirements;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The vetters that must have run, rather than been switched off, before a snapshot is approved
 * automatically (GW_APPROVAL_0044.7). Unset means every configured vetter. A setting that would
 * require nothing — an empty list, or a name no vetter carries — stops startup instead.
 */
@Component
public class RequiredVetters {

    private final List<String> names;
    private final boolean everyVetter;

    @Autowired
    public RequiredVetters(SkillsGatewayProperties properties, List<Vetter> vetters) {
        this(properties.approval().auto().requiredVetters(), vetters);
    }

    RequiredVetters(List<String> configured, List<Vetter> vetters) {
        this.names = validated(configured, vetters);
        this.everyVetter = configured == null;
    }

    /** Unset: every verdict a run carries must not be {@code DISABLED}, named here or not. */
    @Requirements({"GW_APPROVAL_0044.7"})
    public boolean everyVetter() {
        return everyVetter;
    }

    @Requirements({"GW_APPROVAL_0044.7"})
    public List<String> names() {
        return names;
    }

    @Requirements({"GW_APPROVAL_0044.7"})
    private static List<String> validated(List<String> configured, List<Vetter> vetters) {
        Set<String> known = new TreeSet<>();
        vetters.forEach(vetter -> known.add(vetter.name()));
        if (configured == null) {
            return List.copyOf(known);
        }
        if (configured.isEmpty()) {
            throw new IllegalStateException(Auto.REQUIRED_VETTERS_KEY
                    + " is set but empty, which would require no vetter at all; leave it unset to require every"
                    + " vetter, or name the ones that must run");
        }
        for (int i = 0; i < configured.size(); i++) {
            String name = configured.get(i);
            if (name == null || !known.contains(name)) {
                throw new IllegalStateException(
                        "%s[%d] names '%s', which is not a configured vetter; the configured vetters are %s"
                                .formatted(Auto.REQUIRED_VETTERS_KEY, i, name, String.join(", ", known)));
            }
        }
        return List.copyOf(new TreeSet<>(configured));
    }
}
