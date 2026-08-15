package io.github.jimisola.skillsgateway;

import io.github.jimisola.skillsgateway.config.SkillsGatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(SkillsGatewayProperties.class)
// Drives the webhook dispatcher's fixed-delay poll (GW_0025).
@EnableScheduling
public class SkillsGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SkillsGatewayApplication.class, args);
    }
}
