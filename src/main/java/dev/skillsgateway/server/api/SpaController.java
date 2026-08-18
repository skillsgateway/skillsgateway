package dev.skillsgateway.server.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Client-side routes resolve to the SPA entry; assets are served from the jar's static dir. */
@Controller
public class SpaController {

    @GetMapping({"/", "/marketplaces", "/marketplaces/{name}", "/audit", "/adoption", "/tokens", "/webhooks"})
    public String spa() {
        return "forward:/index.html";
    }
}
