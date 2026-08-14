package io.github.jimisola.skillsgateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Scaffold sanity check: proves the compile/test/report pipeline runs. Real verifications (with
 * reqstool SVC annotations) arrive with the port change.
 */
class SanityTest {

    @Test
    void applicationClassIsBootApplication() {
        assertThat(SkillsGatewayApplication.class).hasAnnotation(SpringBootApplication.class);
    }
}
