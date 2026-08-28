package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skillsgateway.server.config.SkillsGatewayProperties.ClaimMapping;
import dev.skillsgateway.server.config.SkillsGatewayProperties.Roles;
import dev.skillsgateway.server.roles.ClaimRoleMapper;
import dev.skillsgateway.server.roles.RoleService.EffectiveRole;
import io.github.reqstool.annotations.SVCs;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

/**
 * The mapper itself, away from HTTP: claim shapes, nested claim paths, the startup validation that
 * refuses a malformed mapping, and a randomized sweep asserting the matcher never throws and never
 * widens (GW_0098, GW_0099).
 */
class ClaimRoleMapperTests {

    private static final List<ClaimMapping> MAPPINGS = List.of(
            new ClaimMapping("gw-admins", "admin", null),
            new ClaimMapping("gw-approvers-a", "approver", "acme"),
            new ClaimMapping("gw-auditors", "auditor", null));

    private static ClaimRoleMapper mapper(String claim) {
        return new ClaimRoleMapper(new Roles(true, List.of(), claim, MAPPINGS));
    }

    private static Authentication oidc(Map<String, Object> claims) {
        Map<String, Object> all = new LinkedHashMap<>(claims);
        all.putIfAbsent("sub", "someone");
        OidcIdToken idToken = new OidcIdToken(
                "token-value", Instant.now().minusSeconds(60), Instant.now().plusSeconds(600), all);
        return UsernamePasswordAuthenticationToken.authenticated(
                new DefaultOidcUser(List.of(), idToken), null, List.of());
    }

    @Test
    @SVCs({"SVC_GW_0098"})
    void a_claim_list_a_lone_string_and_a_nested_path_all_resolve() {
        assertThat(mapper("groups").rolesFrom(oidc(Map.of("groups", List.of("gw-admins", "gw-auditors")))))
                .containsExactlyInAnyOrder(
                        new EffectiveRole("admin", null, EffectiveRole.CLAIM),
                        new EffectiveRole("auditor", null, EffectiveRole.CLAIM));

        assertThat(mapper("groups").rolesFrom(oidc(Map.of("groups", "gw-admins"))))
                .containsExactly(new EffectiveRole("admin", null, EffectiveRole.CLAIM));

        assertThat(mapper("realm_access.roles")
                        .rolesFrom(oidc(Map.of("realm_access", Map.of("roles", List.of("gw-approvers-a"))))))
                .containsExactly(new EffectiveRole("approver", "acme", EffectiveRole.CLAIM));
    }

    @Test
    @SVCs({"SVC_GW_0098"})
    void a_delimited_string_is_one_value_and_is_never_split() {
        // Splitting would mean inventing a delimiter the provider never promised.
        assertThat(mapper("groups").rolesFrom(oidc(Map.of("groups", "gw-admins gw-auditors"))))
                .isEmpty();
        assertThat(mapper("groups").rolesFrom(oidc(Map.of("groups", "gw-admins,gw-auditors"))))
                .isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_0098"})
    void duplicate_and_repeated_claim_values_yield_one_role_each() {
        assertThat(mapper("groups").rolesFrom(oidc(Map.of("groups", List.of("gw-admins", "gw-admins", " gw-admins ")))))
                .containsExactly(new EffectiveRole("admin", null, EffectiveRole.CLAIM));
    }

    @Test
    @SVCs({"SVC_GW_0099"})
    void truncation_is_detected_only_when_the_provider_says_the_claim_was_dropped() {
        ClaimRoleMapper mapper = mapper("groups");
        assertThat(mapper.truncated(oidc(Map.of("hasgroups", true)))).isTrue();
        assertThat(mapper.truncated(oidc(Map.of("_claim_names", Map.of("groups", "src1")))))
                .isTrue();

        // Present-but-empty, absent, and a truncation flag for some other claim are all not it.
        assertThat(mapper.truncated(oidc(Map.of("groups", List.of())))).isFalse();
        assertThat(mapper.truncated(oidc(Map.of()))).isFalse();
        assertThat(mapper.truncated(oidc(Map.of("hasgroups", false)))).isFalse();
        assertThat(mapper.truncated(oidc(Map.of("_claim_names", Map.of("address", "src1")))))
                .isFalse();
        // A provider that both flags and supplies the claim has not truncated it.
        assertThat(mapper.truncated(oidc(Map.of("hasgroups", true, "groups", List.of("gw-admins")))))
                .isFalse();
    }

    @Test
    @SVCs({"SVC_GW_0098"})
    void a_credential_that_is_not_an_identity_provider_session_yields_nothing() {
        Authentication bare = UsernamePasswordAuthenticationToken.authenticated("pat-user", null, List.of());
        assertThat(mapper("groups").rolesFrom(bare)).isEmpty();
        assertThat(mapper("groups").truncated(bare)).isFalse();
    }

    @Test
    @SVCs({"SVC_GW_0098"})
    void a_malformed_mapping_refuses_construction() {
        assertThatThrownBy(() -> new ClaimRoleMapper(
                        new Roles(true, List.of(), "groups", List.of(new ClaimMapping("x", "superuser", null)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("superuser");

        assertThatThrownBy(() -> new ClaimRoleMapper(
                        new Roles(true, List.of(), "groups", List.of(new ClaimMapping("  ", "admin", null)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim-value");

        assertThatThrownBy(() -> new ClaimRoleMapper(
                        new Roles(true, List.of(), "groups", List.of(new ClaimMapping("x", "approver", null)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("marketplace");

        assertThatThrownBy(() -> new ClaimRoleMapper(
                        new Roles(true, List.of(), "groups", List.of(new ClaimMapping("x", "admin", "acme")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("marketplace");

        // A blank claim name is not an error: Roles normalises it to the default claim.
        assertThat(new ClaimRoleMapper(new Roles(true, List.of(), "  ", MAPPINGS))
                        .rolesFrom(oidc(Map.of("groups", List.of("gw-admins")))))
                .containsExactly(new EffectiveRole("admin", null, EffectiveRole.CLAIM));
    }

    /**
     * Randomized sweep over claim payloads a hostile or merely eccentric provider could send.
     * Deterministic seed: a failure is reproducible from the source alone. This is generative
     * testing by hand, not a property-based framework — no such dependency is in the build.
     */
    @Test
    @SVCs({"SVC_GW_0098"})
    void arbitrary_claim_payloads_never_throw_and_never_grant_without_an_exact_match() {
        Random random = new Random(0x5AFE_C1A1L);
        ClaimRoleMapper mapper = mapper("groups");
        for (int i = 0; i < 2000; i++) {
            Object payload = randomPayload(random, 0);
            Authentication authentication = oidc(payload == null ? Map.of() : Map.of("groups", payload));

            List<EffectiveRole> roles = mapper.rolesFrom(authentication);
            mapper.truncated(authentication);

            List<String> exact = exactMatches(payload);
            assertThat(roles).as("payload %s", payload).allSatisfy(role -> assertThat(role.source())
                    .isEqualTo(EffectiveRole.CLAIM));
            assertThat(roles.stream()
                            .map(EffectiveRole::role)
                            .distinct()
                            .sorted()
                            .toList())
                    .as("payload %s", payload)
                    .isEqualTo(exact.stream().distinct().sorted().toList());
        }
    }

    /** What the mapping table says the payload is entitled to, computed independently. */
    private static List<String> exactMatches(Object payload) {
        List<Object> values = payload instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
        if (payload instanceof String single) {
            values = List.of(single);
        }
        List<String> roles = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof String text)) {
                continue;
            }
            for (ClaimMapping mapping : MAPPINGS) {
                if (mapping.claimValue().equals(text.trim())) {
                    roles.add(mapping.role());
                }
            }
        }
        return roles;
    }

    private static Object randomPayload(Random random, int depth) {
        return switch (random.nextInt(depth > 2 ? 6 : 9)) {
            case 0 -> null;
            case 1 -> randomToken(random);
            case 2 -> random.nextInt();
            case 3 -> random.nextBoolean();
            case 4 -> "";
            case 5 -> " ".repeat(random.nextInt(4));
            case 6 -> {
                List<Object> list = new ArrayList<>();
                for (int i = random.nextInt(6); i > 0; i--) {
                    list.add(randomPayload(random, depth + 1));
                }
                yield list;
            }
            case 7 -> {
                Map<String, Object> map = new LinkedHashMap<>();
                for (int i = random.nextInt(3); i > 0; i--) {
                    map.put(randomToken(random), randomPayload(random, depth + 1));
                }
                yield map;
            }
            default -> {
                // A long list dominated by near-misses of a real mapping value.
                List<Object> list = new ArrayList<>();
                for (int i = random.nextInt(50); i > 0; i--) {
                    list.add(randomToken(random));
                }
                yield list;
            }
        };
    }

    private static String randomToken(Random random) {
        String base = MAPPINGS.get(random.nextInt(MAPPINGS.size())).claimValue();
        return switch (random.nextInt(8)) {
            case 0 -> base;
            case 1 -> "  " + base + "  ";
            case 2 -> base.toUpperCase(java.util.Locale.ROOT);
            case 3 -> base.substring(0, base.length() - 1);
            case 4 -> base + "-x";
            case 5 -> "x" + base;
            case 6 -> base.replace('-', '_');
            default -> "unrelated-" + random.nextInt(1000);
        };
    }
}
