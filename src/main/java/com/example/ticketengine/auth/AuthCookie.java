package com.example.ticketengine.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The browser keeps the JWT in an httpOnly cookie, so page scripts cannot read it (limits XSS damage).
 * SameSite=Strict stops other sites from sending it along with their requests (CSRF).
 */
@Component
public class AuthCookie {

    public static final String NAME = "access_token";

    private final boolean secure;

    public AuthCookie(@Value("${ticket.cookie.secure:false}") boolean secure) { this.secure = secure; }

    public ResponseCookie create(String token, Duration maxAge) {
        return base(token).maxAge(maxAge).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(secure).sameSite("Strict").path("/");
    }
}
