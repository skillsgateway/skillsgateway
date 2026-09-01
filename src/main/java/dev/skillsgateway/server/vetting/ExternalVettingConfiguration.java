package dev.skillsgateway.server.vetting;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Wires the operator-configured external vetting connectors into the context (GW_0142). Component
 * scan finds this configuration, which imports {@link ExternalVettingConnectorRegistrar}; the
 * registrar reads {@code skills-gateway.vetting.external[*]} and registers a bean per entry. An
 * empty or absent list registers nothing, so a deployment that configures no external connector
 * behaves exactly as before.
 */
@Configuration(proxyBeanMethods = false)
@Import(ExternalVettingConnectorRegistrar.class)
public class ExternalVettingConfiguration {}
