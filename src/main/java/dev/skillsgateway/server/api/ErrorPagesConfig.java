package dev.skillsgateway.server.api;

import org.apache.catalina.core.StandardHost;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Puts {@link BrandedErrorReportValve} on the Host in place of Tomcat's own (GW_AUTH_0067). */
@Configuration(proxyBeanMethods = false)
public class ErrorPagesConfig {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> brandedErrorReportValve() {
        return factory -> factory.addContextCustomizers(context -> {
            // Named by class rather than added here: the Host adds it at start, after every
            // customizer, so it sits inside any valve a customizer added and reports first.
            if (context.getParent() instanceof StandardHost host) {
                host.setErrorReportValveClass(BrandedErrorReportValve.class.getName());
            }
        });
    }
}
