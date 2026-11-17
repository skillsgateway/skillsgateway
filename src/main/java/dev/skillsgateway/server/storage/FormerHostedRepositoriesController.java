package dev.skillsgateway.server.storage;

import dev.skillsgateway.server.roles.RoleService;
import dev.skillsgateway.server.storage.FormerHostedRepositories.FormerHostedRepository;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * TEMPORARY, with {@link FormerHostedRepositories}: REMOVE-AFTER: two minor releases after the
 * release that ships migration V3__remove_hosted_marketplaces. Administrator only, read-only, and
 * unreachable by every machine credential (it is on {@code MachineApiRegistry}'s unreachable list).
 */
@RestController
@RequestMapping("/api/v1/storage")
public class FormerHostedRepositoriesController {

    private final FormerHostedRepositories repositories;
    private final RoleService roleService;

    public FormerHostedRepositoriesController(FormerHostedRepositories repositories, RoleService roleService) {
        this.repositories = repositories;
        this.roleService = roleService;
    }

    @GetMapping("/former-hosted-repositories")
    @Requirements({"GW_AUTH_0091"})
    @Tag(name = "Storage")
    @Operation(
            summary = "Repositories left by the removed hosted-marketplace feature",
            description = "The repositories of the removed hosted-marketplace feature that are still in the gateway's"
                    + " data store, read from storage on each request, so the list ends when the folders do. Each"
                    + " is the only copy of a publisher's source: copy it (`git clone <location>`) and delete the"
                    + " folder yourself; the gateway never deletes one. An empty list when there is nothing."
                    + " Temporary: removed two minor releases after the release that ships the migration that"
                    + " removed hosting. Administrator only.")
    @ApiResponse(responseCode = "200", description = "The remaining repositories, possibly none")
    @ApiResponse(responseCode = "403", description = "Caller does not hold the administrative role")
    public List<FormerHostedRepository> list(Authentication authentication) {
        roleService.requireAdmin(authentication);
        try {
            return repositories.list();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "could not read the data store", e);
        }
    }
}
