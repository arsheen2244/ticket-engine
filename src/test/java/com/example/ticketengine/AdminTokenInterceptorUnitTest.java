package com.example.ticketengine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Plain unit test of the token comparison; needs no Spring context or databases. */
class AdminTokenInterceptorUnitTest {

    @Test
    void configured_token_only_matches_itself() {
        var guard = new AdminTokenInterceptor("s3cret");
        assertThat(guard.matches("s3cret")).isTrue();
        assertThat(guard.matches("s3cre")).isFalse();
        assertThat(guard.matches("s3cret-and-more")).isFalse();
        assertThat(guard.matches("")).isFalse();
        assertThat(guard.matches(null)).isFalse();
    }

    @Test
    void blank_token_means_open() {
        assertThat(new AdminTokenInterceptor("").matches(null)).isTrue();
        assertThat(new AdminTokenInterceptor("  ").matches("anything")).isTrue();
    }
}
