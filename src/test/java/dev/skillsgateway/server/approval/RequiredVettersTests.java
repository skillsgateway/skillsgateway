package dev.skillsgateway.server.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skillsgateway.server.vetting.SnapshotUnderVetting;
import dev.skillsgateway.server.vetting.Verdict;
import dev.skillsgateway.server.vetting.Vetter;
import io.github.reqstool.annotations.SVCs;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which vetters an automatic approval needs to have run (GW_APPROVAL_0044.7), and the settings that
 * refuse to load because they would require nothing.
 */
class RequiredVettersTests {

    private static final List<Vetter> CHAIN =
            List.of(vetter("secret-scan"), vetter("license-scan"), vetter("prompt-injection"));

    private static Vetter vetter(String name) {
        return new Vetter() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int order() {
                return 0;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public Verdict vet(SnapshotUnderVetting snapshot) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.7"})
    void unsetRequiresEveryConfiguredVetter() {
        List<String> unset = null;
        assertThat(new RequiredVetters(unset, CHAIN).names())
                .containsExactlyInAnyOrder("secret-scan", "license-scan", "prompt-injection");
        assertThat(new RequiredVetters(unset, CHAIN).everyVetter()).isTrue();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.7"})
    void aListRequiresExactlyTheVettersItNames() {
        assertThat(new RequiredVetters(List.of("secret-scan", "prompt-injection"), CHAIN).names())
                .containsExactlyInAnyOrder("secret-scan", "prompt-injection");
        assertThat(new RequiredVetters(List.of("secret-scan"), CHAIN).everyVetter())
                .isFalse();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.7"})
    void startupRefusesAnEmptyList() {
        assertThatThrownBy(() -> new RequiredVetters(List.of(), CHAIN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skills-gateway.approval.auto.required-vetters")
                .hasMessageContaining("empty");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.7"})
    void startupRefusesANameThatIsNotAConfiguredVetter() {
        assertThatThrownBy(() -> new RequiredVetters(List.of("secret-scan", "secrets-scan"), CHAIN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skills-gateway.approval.auto.required-vetters[1]")
                .hasMessageContaining("secrets-scan")
                .hasMessageContaining("license-scan, prompt-injection, secret-scan");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.7"})
    void startupRefusesABlankName() {
        List<String> blank = new ArrayList<>();
        blank.add(" ");
        assertThatThrownBy(() -> new RequiredVetters(blank, CHAIN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skills-gateway.approval.auto.required-vetters[0]");
    }
}
