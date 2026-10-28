package dev.skillsgateway.server.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.SVCs;
import java.util.Optional;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;

/**
 * The virtual catalog is served only while it is enabled (GW_FACADE_0036): disabling it stops its
 * rebuilds, so a catalog left served would keep content that later revocations removed elsewhere.
 */
class DisabledCatalogTests {

    private static GitFacadeConfiguration facade(boolean enabled, Repository catalog) throws Exception {
        GitStorage storage = mock(GitStorage.class);
        when(storage.publishedIfServing("catalog")).thenReturn(Optional.of(catalog));
        SkillsGatewayProperties properties = mock(SkillsGatewayProperties.class);
        when(properties.catalog()).thenReturn(new SkillsGatewayProperties.Catalog(enabled, "catalog"));
        return new GitFacadeConfiguration(
                storage, mock(FetchAuditHook.class), mock(MarketplaceRepository.class), properties);
    }

    @Test
    @SVCs({"SVC_GW_FACADE_0038"})
    void a_disabled_catalog_answers_as_not_found_though_storage_still_holds_it() throws Exception {
        Repository stale = mock(Repository.class);

        assertThatThrownBy(() -> facade(false, stale)
                        .resolvePublished(new org.springframework.mock.web.MockHttpServletRequest(), "catalog.git"))
                .isInstanceOf(RepositoryNotFoundException.class);
    }

    @Test
    @SVCs({"SVC_GW_FACADE_0038"})
    void an_enabled_catalog_is_served() throws Exception {
        Repository served = mock(Repository.class);

        assertThat(facade(true, served)
                        .resolvePublished(new org.springframework.mock.web.MockHttpServletRequest(), "catalog.git"))
                .isSameAs(served);
    }
}
