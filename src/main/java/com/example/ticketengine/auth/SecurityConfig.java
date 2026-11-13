package com.example.ticketengine.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.util.Map;

import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

/**
 * Who may call what.
 * <ul>
 *   <li>Public: sign up, log in, log out, reading events and the live stock stream.</li>
 *   <li>USER or ADMIN: bookings, my orders, /api/auth/me.</li>
 *   <li>ADMIN only: everything under /api/admin.</li>
 *   <li>Legacy benchmark endpoints (the original reserve/create/reset/pay API used by k6 and the
 *       demo page): open while {@code ticket.legacy-endpoints.enabled} is true, blocked otherwise.
 *       Create and reset keep their own X-Admin-Token check.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    static final RequestMatcher PUBLIC = new OrRequestMatcher(
            antMatcher(HttpMethod.POST, "/api/auth/signup"),
            antMatcher(HttpMethod.POST, "/api/auth/login"),
            antMatcher(HttpMethod.POST, "/api/auth/logout"),
            antMatcher(HttpMethod.GET, "/api/events"),
            antMatcher(HttpMethod.GET, "/api/events/**"),
            antMatcher(HttpMethod.GET, "/"),
            antMatcher(HttpMethod.GET, "/index.html"),
            antMatcher(HttpMethod.GET, "/favicon.ico"),
            antMatcher(HttpMethod.GET, "/actuator/health"),
            antMatcher(HttpMethod.GET, "/actuator/prometheus"),
            antMatcher("/error"));

    static final RequestMatcher LEGACY = new OrRequestMatcher(
            antMatcher(HttpMethod.POST, "/api/events"),
            antMatcher(HttpMethod.POST, "/api/events/*/reserve"),
            antMatcher(HttpMethod.POST, "/api/events/*/reset"),
            antMatcher("/api/reservations/**"));

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper,
                                            @Value("${ticket.legacy-endpoints.enabled:true}") boolean legacyEnabled)
            throws Exception {
        JsonSecurityErrors errors = new JsonSecurityErrors(mapper);
        http
                // No server-side session: every request carries its JWT.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // CSRF tokens are not needed: the auth cookie is SameSite=Strict and the API only accepts JSON.
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> {
                    if (legacyEnabled) auth.requestMatchers(LEGACY).permitAll();
                    else auth.requestMatchers(LEGACY).denyAll();
                    auth.requestMatchers(PUBLIC).permitAll()
                            .requestMatchers("/api/admin/**").hasRole(Role.ADMIN.name())
                            .requestMatchers("/api/auth/me", "/api/bookings/**", "/api/me/**").authenticated()
                            .anyRequest().denyAll();
                })
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(tokenResolver())
                        .jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    /**
     * Reads the token from "Authorization: Bearer ..." (scripts, k6) or from the auth cookie (browser).
     * Public and legacy endpoints ignore tokens entirely, so an expired cookie never breaks a public page.
     */
    static BearerTokenResolver tokenResolver() {
        DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();
        return request -> {
            if (PUBLIC.matches(request) || LEGACY.matches(request)) return null;
            String fromHeader = header.resolve(request);
            return fromHeader != null ? fromHeader : cookieToken(request);
        };
    }

    static String cookieToken(HttpServletRequest request) {
        var cookie = WebUtils.getCookie(request, AuthCookie.NAME);
        return cookie == null || cookie.getValue().isBlank() ? null : cookie.getValue();
    }

    /** Turns the "roles" claim into ROLE_USER / ROLE_ADMIN authorities. */
    static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName(JwtService.ROLES_CLAIM);
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return converter;
    }

    /** 401 and 403 as small JSON bodies the frontend can show. */
    record JsonSecurityErrors(ObjectMapper mapper) implements
            org.springframework.security.web.AuthenticationEntryPoint,
            org.springframework.security.web.access.AccessDeniedHandler {

        @Override
        public void commence(HttpServletRequest req, HttpServletResponse res,
                             org.springframework.security.core.AuthenticationException e) throws IOException {
            write(res, HttpServletResponse.SC_UNAUTHORIZED, "Please log in");
        }

        @Override
        public void handle(HttpServletRequest req, HttpServletResponse res,
                           org.springframework.security.access.AccessDeniedException e) throws IOException {
            write(res, HttpServletResponse.SC_FORBIDDEN, "You do not have access to this");
        }

        private void write(HttpServletResponse res, int status, String message) throws IOException {
            res.setStatus(status);
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(res.getOutputStream(), Map.of("error", message));
        }
    }
}
