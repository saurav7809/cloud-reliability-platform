package io.aegiscloud.controlplane.web;

import io.aegiscloud.controlplane.auth.AccountService;
import io.aegiscloud.controlplane.auth.AuthenticatedUser;
import io.aegiscloud.controlplane.auth.CurrentUser;
import io.aegiscloud.controlplane.auth.Role;
import io.aegiscloud.controlplane.auth.Tenant;
import io.aegiscloud.controlplane.auth.TokenManager;
import io.aegiscloud.controlplane.auth.UserStore;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserStore users;
    private final TokenManager tokens;
    private final AccountService accounts;

    public AuthController(UserStore users, TokenManager tokens, AccountService accounts) {
        this.users = users;
        this.tokens = tokens;
        this.accounts = accounts;
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

    public record SignUpRequest(String email, String password, String organisationName) {
    }

    /**
     * Creates a new organisation and its first administrator.
     *
     * <p>Public, and safe to be public because of what it creates: a brand-new
     * organisation with nothing in it. Tenant isolation is enforced in every query,
     * so a stranger who signs up sees no cluster, service or incident belonging to
     * anyone else. Before that boundary existed this endpoint would have handed a
     * passer-by the whole fleet.
     *
     * <p>Deployments that want accounts created by an administrator instead can set
     * {@code aegiscloud.signup.enabled=false}, and this answers 403 saying so.
     */
    @PostMapping("/signup")
    public LoginResponse signUp(@RequestBody(required = false) SignUpRequest request) {
        if (request == null) {
            throw ApiException.badRequest("invalid request body");
        }

        try {
            AuthenticatedUser user = accounts.signUp(
                    request.email(), request.password(), request.organisationName());

            // Signed in immediately. Making someone register and then log in with
            // the credentials they just typed is friction with no security value.
            return new LoginResponse(tokens.issue(user), user.role().name());

        } catch (AccountService.RegistrationRefused e) {
            if (!accounts.signupEnabled()) {
                throw ApiException.forbidden(e.getMessage());
            }
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** Whether this deployment accepts self-service registration, for the login screen. */
    @GetMapping("/signup/enabled")
    public Map<String, Object> signupEnabled() {
        return Map.of("enabled", accounts.signupEnabled());
    }

    public record InviteRequest(String email, String password, String role) {
    }

    /**
     * Adds a user to the caller's own organisation.
     *
     * <p>ADMIN only, and the organisation comes from the token rather than the
     * request: an organisation id in the body would let one administrator create
     * accounts inside another tenant.
     */
    @PostMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> invite(@RequestBody(required = false) InviteRequest request) {
        if (request == null) {
            throw ApiException.badRequest("invalid request body");
        }

        Role role;
        try {
            role = Role.valueOf(request.role() == null
                    ? "VIEWER" : request.role().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("role must be ADMIN, OPERATOR or VIEWER");
        }

        try {
            AuthenticatedUser created = accounts.invite(
                    Tenant.currentOrgId(), request.email(), request.password(), role);
            return Map.of("id", created.id(), "email", created.email(),
                    "role", created.role().name());
        } catch (AccountService.RegistrationRefused e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** The organisation's members. */
    @GetMapping("/users")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public List<AccountService.Member> members() {
        return accounts.members(Tenant.currentOrgId());
    }

    public record RoleChangeRequest(String role) {
    }

    @PostMapping("/users/{userId}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> changeRole(@PathVariable String userId,
                                          @RequestBody RoleChangeRequest request) {
        Role role;
        try {
            role = Role.valueOf(request.role().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.badRequest("role must be ADMIN, OPERATOR or VIEWER");
        }

        try {
            accounts.changeRole(Tenant.currentOrgId(), UUID.fromString(userId), role);
            return Map.of("id", userId, "role", role.name());
        } catch (AccountService.RegistrationRefused e) {
            throw ApiException.badRequest(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("no such user");
        }
    }

    @GetMapping("/me")
    public Map<String, String> me() {
        AuthenticatedUser user = CurrentUser.get();
        return Map.of("email", user.email(), "role", user.role().name());
    }
}
