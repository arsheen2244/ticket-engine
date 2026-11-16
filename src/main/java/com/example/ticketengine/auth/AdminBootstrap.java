package com.example.ticketengine.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Sign-up always creates USER accounts. The first ADMIN comes from the ADMIN_EMAIL and ADMIN_PASSWORD
 * environment variables: at startup that account is created, or promoted to ADMIN if it exists.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String email;
    private final String password;

    public AdminBootstrap(UserRepository users, PasswordEncoder encoder,
                          @Value("${ticket.admin.email:}") String email,
                          @Value("${ticket.admin.password:}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) {
            log.info("ADMIN_EMAIL/ADMIN_PASSWORD not set: no admin account created");
            return;
        }
        if (password.length() < 8) throw new IllegalStateException("ADMIN_PASSWORD must be at least 8 characters");
        var existing = users.findByEmail(email);
        if (existing.isEmpty()) {
            users.create(email, encoder.encode(password), Role.ADMIN);
            log.info("Created admin account {}", email);
        } else if (existing.get().role() != Role.ADMIN) {
            users.setRole(existing.get().id(), Role.ADMIN);
            log.info("Promoted {} to admin", email);
        }
    }
}
