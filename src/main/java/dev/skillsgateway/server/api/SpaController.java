package dev.skillsgateway.server.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Client-side routes resolve to the SPA entry; assets are served from the jar's static dir.
 *
 * <p>This list is the server half of the portal's routing, and it has to hold every path the
 * router registers: a route missing here works while the browser is navigating within the
 * application and answers 404 the moment someone opens it cold — which is exactly what a pasted
 * link is. {@code SpaRoutesTests} reads the router and fails when the two disagree.
 */
@Controller
public class SpaController {

    @GetMapping({
        "/",
        "/marketplaces",
        "/marketplaces/{name}",
        "/marketplaces/{name}/snapshots",
        "/marketplaces/{name}/activity",
        "/marketplaces/{name}/settings",
        "/marketplaces/{name}/snapshots/{id}/files",
        "/review",
        "/audit",
        "/vetting",
        "/adoption",
        "/tokens",
        "/webhooks"
    })
    public String spa() {
        return "forward:/index.html";
    }
}
