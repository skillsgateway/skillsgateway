package dev.skillsgateway.server.vetting;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Turns {@code skills-gateway.vetting.external[*]} into one {@link ExternalVettingConnector} bean
 * per entry (GW_0142). Registering them as ordinary beans means they join the chain through the
 * same {@code List<VettingConnector>} injection the built-ins do — {@code VettingService} is
 * untouched, and the ordering, recording and fail-closed aggregation apply identically.
 *
 * <p>The list is bound here from the {@link Environment} rather than read from a bound
 * {@code @ConfigurationProperties} bean because bean definitions must be registered before those
 * beans exist. {@code Binder.get(environment)} resolves {@code ${...}} placeholders, so a credential
 * referenced as an environment variable is never inlined.
 *
 * <p>Two invariants are enforced at startup, so a misconfiguration fails loudly rather than
 * producing a chain that silently drops or shadows a connector:
 *
 * <ul>
 *   <li>a duplicate external name is a startup failure;
 *   <li>an external name that collides with a built-in is a startup failure (enforced in {@link
 *       ExternalConnectorProperties}), because two connectors sharing a name break the continuity of
 *       a snapshot's vetting history.
 * </ul>
 */
public class ExternalVettingConnectorRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(ExternalVettingConnectorRegistrar.class);

    /** Configuration prefix the external connectors are bound from. */
    public static final String PREFIX = "skills-gateway.vetting.external";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        List<ExternalConnectorProperties> connectors = Binder.get(environment)
                .bind(PREFIX, Bindable.listOf(ExternalConnectorProperties.class))
                .orElse(List.of());
        Set<String> seen = new LinkedHashSet<>();
        for (ExternalConnectorProperties props : connectors) {
            String key = props.name().toLowerCase(Locale.ROOT);
            if (!seen.add(key)) {
                throw new IllegalStateException(
                        "duplicate external vetting connector name '" + props.name() + "' in " + PREFIX);
            }
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(ExternalVettingConnector.class);
            builder.addConstructorArgValue(props);
            registry.registerBeanDefinition("externalVettingConnector-" + props.name(), builder.getBeanDefinition());
            log.info(
                    "registered external vetting connector '{}' (order {}, version {}) -> {}",
                    props.name(),
                    props.order(),
                    props.version(),
                    props.url());
        }
    }
}
