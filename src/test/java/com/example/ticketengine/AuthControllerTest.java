package com.example.ticketengine;

import com.example.ticketengine.auth.AuthCookie;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Sign up, log in, log out and /me. Needs Postgres and Redis. */
@SpringBootTest(properties = {"ticket.admin.email=boss@example.com", "ticket.admin.password=admin-pass-123"})
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void signup_creates_a_user_account_and_sets_an_httponly_cookie() throws Exception {
        String email = TestAuth.uniqueEmail();
        var result = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(TestAuth.credentials(email, TestAuth.PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andReturn();
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).startsWith(AuthCookie.NAME + "=").contains("HttpOnly").contains("SameSite=Strict");
    }

    @Test
    void signup_with_taken_email_is_409_case_insensitive() throws Exception {
        String email = TestAuth.uniqueEmail();
        TestAuth.signup(mvc, email);
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(TestAuth.credentials(email.toUpperCase(), TestAuth.PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("already exists")));
    }

    @Test
    void signup_validates_email_and_password() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(TestAuth.credentials("not-an-email", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("email")))
                .andExpect(jsonPath("$.error").value(containsString("password")));
    }

    @Test
    void login_succeeds_with_right_password_only() throws Exception {
        var account = TestAuth.signup(mvc);
        TestAuth.login(mvc, account.email(), TestAuth.PASSWORD);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(TestAuth.credentials(account.email(), "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid email or password"));
    }

    @Test
    void login_with_unknown_email_gives_the_same_401() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(TestAuth.credentials(TestAuth.uniqueEmail(), TestAuth.PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid email or password"));
    }

    @Test
    void me_needs_a_valid_token_from_header_or_cookie() throws Exception {
        var account = TestAuth.signup(mvc);
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Please log in"));
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, account.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(account.email()));
        mvc.perform(get("/api/auth/me").cookie(new Cookie(AuthCookie.NAME, account.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(account.id()));
    }

    @Test
    void tampered_token_is_rejected() throws Exception {
        var account = TestAuth.signup(mvc);
        String token = account.token();
        String tampered = token.substring(0, token.length() - 3) + (token.endsWith("AAA") ? "BBB" : "AAA");
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_revokes_the_token_and_clears_the_cookie() throws Exception {
        var account = TestAuth.signup(mvc);
        var result = mvc.perform(post("/api/auth/logout").cookie(new Cookie(AuthCookie.NAME, account.token())))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, account.bearer()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_without_a_token_still_succeeds() throws Exception {
        mvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
    }

    @Test
    void admin_account_comes_from_environment() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(TestAuth.credentials("boss@example.com", "admin-pass-123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("ADMIN"));
    }

    @Test
    void public_reserve_endpoint_rejects_account_user_ids() throws Exception {
        mvc.perform(post("/api/events/1/reserve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"acct-1\",\"qty\":1}"))
                .andExpect(status().isBadRequest());
    }
}
