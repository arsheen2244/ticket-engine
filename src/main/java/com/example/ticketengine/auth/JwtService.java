package com.example.ticketengine.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Issues the access token a user gets after signing up or logging in. */
@Service
public class JwtService {

    public static final String ISSUER = "ticket-engine";
    public static final String ROLES_CLAIM = "roles";
    public static final String EMAIL_CLAIM = "email";

    public record IssuedToken(String value, Instant expiresAt) {}

    private final JwtEncoder encoder;
    private final Duration ttl;

    public JwtService(JwtEncoder encoder, @Value("${ticket.jwt.ttl-minutes:60}") long ttlMinutes) {
        this.encoder = encoder;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public Duration ttl() { return ttl; }

    public IssuedToken issue(AppUser user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(user.id()))
                .id(UUID.randomUUID().toString()) // jti: lets logout revoke this one token
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(EMAIL_CLAIM, user.email())
                .claim(ROLES_CLAIM, List.of(user.role().name()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return new IssuedToken(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), expiresAt);
    }
}
