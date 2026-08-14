package io.github.jimisola.skillsgateway;

import io.github.jimisola.skillsgateway.config.SkillsGatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(SkillsGatewayProperties.class)
public class SkillsGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SkillsGatewayApplication.class, args);
    }
}
