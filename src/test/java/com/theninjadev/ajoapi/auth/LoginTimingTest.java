package com.theninjadev.ajoapi.auth;

import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A login for an unknown phone must do the same BCrypt work as a wrong password, so response
 * time does not reveal which phone numbers are registered.
 */
@SpringBootTest
class LoginTimingTest extends AbstractIntegrationTest {

    /** Wraps the real encoder and records every matches() call. Test-only. */
    static class RecordingPasswordEncoder implements PasswordEncoder {
        private final PasswordEncoder delegate = new BCryptPasswordEncoder();
        final List<String[]> matchesCalls = new ArrayList<>();

        @Override
        public String encode(CharSequence rawPassword) {
            return delegate.encode(rawPassword);
        }

        @Override
        public synchronized boolean matches(CharSequence rawPassword, String encodedPassword) {
            matchesCalls.add(new String[]{rawPassword.toString(), encodedPassword});
            return delegate.matches(rawPassword, encodedPassword);
        }
    }

    @TestConfiguration
    static class RecordingEncoderConfig {
        @Bean
        @Primary
        RecordingPasswordEncoder recordingPasswordEncoder() {
            return new RecordingPasswordEncoder();
        }
    }

    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private RecordingPasswordEncoder encoder;

    @Test
    void anUnknownPhoneStillRunsOneBcryptCheckAtTheSameCost() {
        var registered = authService.register(new RegisterRequest("08012347001", "password123", "Real User", null));
        String realHash = userRepository.findById(registered.user().getId()).orElseThrow().getPasswordHash();

        encoder.matchesCalls.clear();
        assertThrows(InvalidCredentialsException.class,
                () -> authService.login(new LoginRequest("08012347999", "guess-123")));

        assertThat(encoder.matchesCalls).hasSize(1);
        String[] call = encoder.matchesCalls.getFirst();
        assertThat(call[0]).isEqualTo("guess-123");
        assertThat(call[1]).startsWith("$2");
        // "$2a$10$…": the version and cost prefix must match a real user's hash — same work factor.
        assertThat(call[1].substring(0, 7)).isEqualTo(realHash.substring(0, 7));
    }

    @Test
    void aWrongPasswordRunsExactlyOneBcryptCheckToo() {
        authService.register(new RegisterRequest("08012347002", "password123", "Real User", null));

        encoder.matchesCalls.clear();
        assertThrows(InvalidCredentialsException.class,
                () -> authService.login(new LoginRequest("08012347002", "wrong-password")));

        assertThat(encoder.matchesCalls).hasSize(1);
    }
}
