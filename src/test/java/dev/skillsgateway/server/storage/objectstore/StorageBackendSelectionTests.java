package dev.skillsgateway.server.storage.objectstore;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.storage.FilesystemGitStorage;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.storage.GitStorageConfiguration;
import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * The backend is named, and a name that cannot be honoured is a failed start.
 *
 * <p>Every case here is one shape of the same rule: there is no inference and no fallback. A
 * gateway that quietly served from local disk while the operator believed it was serving from a
 * bucket would be healthy, wrong, and undetectable from outside — the same class of defect as a
 * volume that silently loses published content. So the interesting assertions are the refusals,
 * and each of them checks that the refusal <em>says which setting</em> it was decided by; a
 * refusal nobody can act on is only marginally better than a silent fallback.
 */
class StorageBackendSelectionTests {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(Properties.class, GitStorageConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SkillsGatewayProperties.class)
    static class Properties {}

    // no selection at all is the filesystem, so an upgrade changes nothing
    @Test
    @SVCs({"SVC_GW_0111"})
    void absentSelectionIsTheFilesystem() {
        contexts.run(context -> assertThat(context).getBean(GitStorage.class).isInstanceOf(FilesystemGitStorage.class));
    }

    // naming the filesystem resolves the filesystem and nothing else
    @Test
    @SVCs({"SVC_GW_0111"})
    void namingTheFilesystemResolvesTheFilesystem() {
        contexts.withPropertyValues("skills-gateway.storage.backend=filesystem")
                .run(context -> assertThat(context).getBean(GitStorage.class).isInstanceOf(FilesystemGitStorage.class));
    }

    // naming the object store resolves the object store and nothing else
    @Test
    @SVCs({"SVC_GW_0111"})
    void namingTheObjectStoreResolvesTheObjectStore() {
        contexts.withBean(ObjectStoreClient.class, NonWritingObjectStoreClient::new)
                .withPropertyValues(
                        "skills-gateway.storage.backend=object-store",
                        "skills-gateway.storage.object-store.bucket=skills",
                        "skills-gateway.storage.object-store.region=eu-north-1")
                .run(context ->
                        assertThat(context).getBean(GitStorage.class).isInstanceOf(ObjectStoreGitStorage.class));
    }

    // an unrecognised backend fails the start, naming the setting and the value
    @Test
    @SVCs({"SVC_GW_0111"})
    void anUnrecognisedBackendFailsTheStart() {
        contexts.withPropertyValues("skills-gateway.storage.backend=magic").run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .hasStackTraceContaining("skills-gateway.storage.backend")
                .hasStackTraceContaining("magic"));
    }

    // the object store without a bucket fails the start rather than falling back to disk
    @Test
    @SVCs({"SVC_GW_0111"})
    void theObjectStoreWithoutABucketFailsTheStart() {
        contexts.withBean(ObjectStoreClient.class, NonWritingObjectStoreClient::new)
                .withPropertyValues(
                        "skills-gateway.storage.backend=object-store",
                        "skills-gateway.storage.object-store.region=eu-north-1")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("skills-gateway.storage.object-store.bucket"));
    }

    // the object store without a region fails the start
    @Test
    @SVCs({"SVC_GW_0111"})
    void theObjectStoreWithoutARegionFailsTheStart() {
        contexts.withBean(ObjectStoreClient.class, NonWritingObjectStoreClient::new)
                .withPropertyValues(
                        "skills-gateway.storage.backend=object-store",
                        "skills-gateway.storage.object-store.bucket=skills")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("skills-gateway.storage.object-store.region"));
    }

    // static credentials without keys fail the start, naming the missing key
    @Test
    @SVCs({"SVC_GW_0111"})
    void staticCredentialsWithoutKeysFailTheStart() {
        contexts.withBean(ObjectStoreClient.class, NonWritingObjectStoreClient::new)
                .withPropertyValues(
                        "skills-gateway.storage.backend=object-store",
                        "skills-gateway.storage.object-store.bucket=skills",
                        "skills-gateway.storage.object-store.region=eu-north-1",
                        "skills-gateway.storage.object-store.credentials.mode=static")
                .run(context ->
                        assertThat(context).hasFailed().getFailure().hasMessageContaining("credentials.access-key-id"));
    }

    /**
     * Web identity is the mechanism the first deployment target has to use, because it has no
     * instance metadata service. An unresolvable federation must therefore be a failed start and
     * not a walk down the default chain to a metadata endpoint that never answers — which would
     * surface as a timeout in the middle of an approval.
     */
    // web identity with neither configuration nor environment fails the start
    @Test
    @SVCs({"SVC_GW_0111"})
    void webIdentityWithoutARoleFailsTheStart() {
        contexts.withBean(ObjectStoreClient.class, NonWritingObjectStoreClient::new)
                .withPropertyValues(
                        "skills-gateway.storage.backend=object-store",
                        "skills-gateway.storage.object-store.bucket=skills",
                        "skills-gateway.storage.object-store.region=eu-north-1",
                        "skills-gateway.storage.object-store.credentials.mode=web-identity")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("AWS_WEB_IDENTITY_TOKEN_FILE"));
    }

    // web identity configured explicitly starts, holding no secret at all
    @Test
    @SVCs({"SVC_GW_0111"})
    void webIdentityConfiguredExplicitlyStarts() {
        contexts.withBean(ObjectStoreClient.class, NonWritingObjectStoreClient::new)
                .withPropertyValues(
                        "skills-gateway.storage.backend=object-store",
                        "skills-gateway.storage.object-store.bucket=skills",
                        "skills-gateway.storage.object-store.region=eu-north-1",
                        "skills-gateway.storage.object-store.credentials.mode=web-identity",
                        "skills-gateway.storage.object-store.credentials.role-arn=arn:aws:iam::1:role/gateway",
                        "skills-gateway.storage.object-store.credentials.token-file=/var/run/secrets/token")
                .run(context ->
                        assertThat(context).getBean(GitStorage.class).isInstanceOf(ObjectStoreGitStorage.class));
    }
}
