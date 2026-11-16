package com.example.ticketengine.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * JWTs are stateless, so "log out" means remembering the token's id (jti) until it would have
 * expired anyway. Any token on this list is rejected by the decoder.
 */
@Component
public class TokenDenylist {

    private final StringRedisTemplate redis;

    public TokenDenylist(StringRedisTemplate redis) { this.redis = redis; }

    private static String key(String jti) { return "jwt:deny:" + jti; }

    public void deny(String jti, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (jti == null || ttl.isNegative() || ttl.isZero()) return; // already expired: nothing to do
        redis.opsForValue().set(key(jti), "1", ttl);
    }

    public boolean isDenied(String jti) {
        return jti != null && Boolean.TRUE.equals(redis.hasKey(key(jti)));
    }
}
