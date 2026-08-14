package io.github.jimisola.skillsgateway.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Session identity for the portal: the BFF session is the only credential the browser holds. */
@RestController
public class MeController {

    @GetMapping("/api/me")
    @Tag(name = "Session")
    @Operation(summary = "Current user", description = "Username of the authenticated browser session.")
    public Map<String, String> me(Authentication authentication) {
        return Map.of("username", authentication.getName());
    }
}
