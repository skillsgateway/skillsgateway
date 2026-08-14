package io.github.jimisola.skillsgateway.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Client-side routes resolve to the SPA entry; assets are served from the jar's static dir. */
@Controller
public class SpaController {

    @GetMapping({"/", "/marketplaces", "/audit", "/tokens"})
    public String spa() {
        return "forward:/index.html";
    }
}
