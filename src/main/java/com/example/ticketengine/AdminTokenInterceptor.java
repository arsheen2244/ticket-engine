package com.example.ticketengine;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Guards the admin endpoints (create event, reset event) with a shared secret sent in the
 * {@code X-Admin-Token} header. If {@code ADMIN_TOKEN} is not set, the endpoints stay open so local
 * development and the k6 scripts work without extra setup, and a warning is logged at startup.
 */
@Component
public class AdminTokenInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Admin-Token";
    private static final Logger log = LoggerFactory.getLogger(AdminTokenInterceptor.class);

    private final byte[] expectedHash;

    public AdminTokenInterceptor(@Value("${ticket.admin-token:}") String adminToken) {
        if (adminToken == null || adminToken.isBlank()) {
            this.expectedHash = null;
            log.warn("ADMIN_TOKEN is not set: POST /api/events and POST /api/events/{id}/reset are open to anyone. "
                    + "Set ADMIN_TOKEN before deploying publicly.");
        } else {
            this.expectedHash = sha256(adminToken);
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!"POST".equalsIgnoreCase(request.getMethod()) || matches(request.getHeader(HEADER))) {
            return true;
        }
        response.sendError(HttpStatus.UNAUTHORIZED.value(), "Missing or invalid " + HEADER);
        return false;
    }

    /** True when no token is configured, or when the given token equals the configured one. */
    boolean matches(String provided) {
        if (expectedHash == null) return true;
        if (provided == null) return false;
        // Hashing first gives both sides the same length; MessageDigest.isEqual is constant-time.
        return MessageDigest.isEqual(expectedHash, sha256(provided));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }
}
