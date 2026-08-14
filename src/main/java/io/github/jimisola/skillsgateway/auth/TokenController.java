package io.github.jimisola.skillsgateway.auth;

import io.github.jimisola.skillsgateway.persistence.AccessToken;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tokens")
public class TokenController {

    private final TokenService tokenService;

    public TokenController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    public record CreateTokenRequest(String name) {}

    /** Never exposes the stored hash. */
    public record TokenView(long id, String name, Instant createdAt, Instant revokedAt) {}

    @PostMapping
    public ResponseEntity<TokenService.IssuedToken> create(
            @RequestBody CreateTokenRequest request, Authentication authentication) {
        TokenService.IssuedToken issued = tokenService.create(authentication.getName(), request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(issued);
    }

    @GetMapping
    public List<TokenView> list(Authentication authentication) {
        return tokenService.list(authentication.getName()).stream()
                .map(TokenController::view)
                .toList();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable long id, Authentication authentication) {
        return tokenService.revoke(id, authentication.getName())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private static TokenView view(AccessToken token) {
        return new TokenView(token.id(), token.name(), token.createdAt(), token.revokedAt());
    }
}
