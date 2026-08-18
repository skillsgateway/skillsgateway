package dev.skillsgateway.server.estate;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import io.github.reqstool.annotations.Requirements;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Reconciles the declared estate at startup (GW_0083): {@code afterSingletonsInstantiated} runs
 * after every singleton exists — Flyway has migrated, because the repositories the reconciler uses
 * depend on the migrated DataSource — and before the lifecycle phase that starts the web server,
 * so the declaration is in force from the first request. A declared entry that fails validation is
 * reported, never fatal (GW_0087); an infrastructure failure still fails startup.
 */
@Component
public class EstateBootstrap implements SmartInitializingSingleton {

    private final EstateReconciler reconciler;
    private final SkillsGatewayProperties properties;

    public EstateBootstrap(EstateReconciler reconciler, SkillsGatewayProperties properties) {
        this.reconciler = reconciler;
        this.properties = properties;
    }

    @Override
    @Requirements({"GW_0083"})
    public void afterSingletonsInstantiated() {
        reconciler.reconcile(properties.estate(), "startup");
    }
}
