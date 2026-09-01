package io.aegiscloud.controlplane.web;

import io.aegiscloud.controlplane.auth.AuthenticatedUser;
import io.aegiscloud.controlplane.auth.CurrentUser;
import io.aegiscloud.controlplane.auth.TokenManager;
import io.aegiscloud.controlplane.auth.UserStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserStore users;
    private final TokenManager tokens;

    public AuthController(UserStore users, TokenManager tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    public record LoginRequest(String email, String password) {
    }

    public record LoginResponse(String accessToken, String role) {
    }

    /**
     * Exchanges credentials for a bearer token.
     *
     * <p>A missing account and a wrong password produce the same 401, so this
     * endpoint cannot be used to enumerate which addresses are registered.
     */
    @PostMapping("/login")
    public LoginResponse login(@RequestBody(required = false) LoginRequest request) {
        if (request == null) {
            throw ApiException.badRequest("invalid request body");
        }

        AuthenticatedUser user = users.authenticate(request.email(), request.password());
        if (user == null) {
            throw ApiException.unauthorized("invalid email or password");
        }
        return new LoginResponse(tokens.issue(user), user.role().name());
    }

    @GetMapping("/me")
    public Map<String, String> me() {
        AuthenticatedUser user = CurrentUser.get();
        return Map.of("email", user.email(), "role", user.role().name());
    }
}
