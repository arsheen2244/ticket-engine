package com.example.ticketengine.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** BCrypt only reads the first 72 bytes of a password, so longer ones are refused. */
    public record Credentials(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72) String password) {}

    public record UserView(long id, String email, Role role) {
        static UserView of(AppUser u) { return new UserView(u.id(), u.email(), u.role()); }
    }

    /** The token is also in the body for scripts and k6; the browser uses the httpOnly cookie. */
    public record AuthResponse(UserView user, String token, Instant expiresAt) {}

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final JwtDecoder decoder;
    private final TokenDenylist denylist;
    private final AuthCookie cookie;
    /** Compared against when the email is unknown, so both failure paths take the same time. */
    private final String dummyHash;

    public AuthController(UserRepository users, PasswordEncoder encoder, JwtService jwt, JwtDecoder decoder,
                          TokenDenylist denylist, AuthCookie cookie) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.decoder = decoder;
        this.denylist = denylist;
        this.cookie = cookie;
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody Credentials req) {
        AppUser user;
        try {
            user = users.create(req.email(), encoder.encode(req.password()), Role.USER);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
        }
        return signedIn(HttpStatus.CREATED, user);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody Credentials req) {
        var user = users.findByEmail(req.email());
        boolean ok = encoder.matches(req.password(), user.map(AppUser::passwordHash).orElse(dummyHash));
        if (user.isEmpty() || !ok) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
        return signedIn(HttpStatus.OK, user.get());
    }

    /** Revokes the current token (if it is still valid) and clears the cookie. Always succeeds. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        String token = new DefaultBearerTokenResolver().resolve(request);
        if (token == null) token = SecurityConfig.cookieToken(request);
        if (token != null) {
            try {
                Jwt decoded = decoder.decode(token);
                denylist.deny(decoded.getId(), decoded.getExpiresAt());
            } catch (JwtException ignored) {
                // expired, tampered or already logged out: nothing to revoke
            }
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.clear().toString()).build();
    }

    @GetMapping("/me")
    public UserView me(@AuthenticationPrincipal Jwt token) {
        return users.findById(CurrentUser.accountId(token)).map(UserView::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));
    }

    private ResponseEntity<AuthResponse> signedIn(HttpStatus status, AppUser user) {
        JwtService.IssuedToken token = jwt.issue(user);
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookie.create(token.value(), jwt.ttl()).toString())
                .body(new AuthResponse(UserView.of(user), token.value(), token.expiresAt()));
    }
}
