package com.theninjadev.ajoapi.auth;

import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
import com.theninjadev.ajoapi.testsupport.AdjustableClock;
import com.theninjadev.ajoapi.testsupport.AdjustableClockConfig;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Import(AdjustableClockConfig.class)
class AuthServiceTest extends AbstractIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private Clock clock;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void registerPersistsNormalizedPhone() {
        var tokens = authService.register(registerRequest("0801 234 5671", "password123", null));

        var saved = userRepository.findByPhone("+2348012345671");
        assertThat(saved).isPresent();
        assertThat(saved.get().getId()).isEqualTo(tokens.user().getId());
    }

    @Test
    void registeringSamePhoneInDifferentSpellingIsRejectedAsDuplicate() {
        authService.register(registerRequest("08012345672", "password123", null));

        assertThrows(DuplicatePhoneException.class,
                () -> authService.register(registerRequest("+2348012345672", "password123", null)));
    }

    @Test
    void registeringDuplicateEmailIsRejected() {
        authService.register(registerRequest("08012345673", "password123", "dup@example.com"));

        assertThrows(DuplicateEmailException.class,
                () -> authService.register(registerRequest("08012345674", "password123", "dup@example.com")));
    }

    @Test
    void registeringInvalidPhoneIsRejectedBeforeTouchingTheDatabase() {
        long before = userRepository.count();

        assertThrows(InvalidPhoneNumberException.class,
                () -> authService.register(registerRequest("0612345675", "password123", null)));

        assertThat(userRepository.count()).isEqualTo(before);
    }

    @Test
    void loginSucceedsWithCorrectCredentials() {
        authService.register(registerRequest("08012345676", "password123", null));

        var tokens = authService.login(new LoginRequest("08012345676", "password123"));

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();
    }

    @Test
    void loginFailsIdenticallyForWrongPasswordAndUnknownPhone() {
        authService.register(registerRequest("08012345677", "password123", null));

        var wrongPassword = assertThrows(InvalidCredentialsException.class,
                () -> authService.login(new LoginRequest("08012345677", "wrongpassword")));
        var unknownPhone = assertThrows(InvalidCredentialsException.class,
                () -> authService.login(new LoginRequest("08012345699", "password123")));

        assertThat(wrongPassword.getMessage()).isEqualTo(unknownPhone.getMessage());
    }

    @Test
    void refreshRotatesTokenAndRevokesThePrevious() {
        var registered = authService.register(registerRequest("08012345678", "password123", null));

        var rotated = authService.refresh(registered.refreshToken());

        assertThat(rotated.refreshToken()).isNotEqualTo(registered.refreshToken());
        assertThat(rotated.accessToken()).isNotEqualTo(registered.accessToken());
    }

    @Test
    void reusingARotatedRefreshTokenFails() {
        var registered = authService.register(registerRequest("08012345679", "password123", null));

        authService.refresh(registered.refreshToken());

        assertThrows(InvalidRefreshTokenException.class, () -> authService.refresh(registered.refreshToken()));
    }

    @Test
    void refreshAfterExpiryFails() {
        var registered = authService.register(registerRequest("08012345680", "password123", null));

        ((AdjustableClock) clock).advanceBy(Duration.ofDays(31));

        assertThrows(InvalidRefreshTokenException.class, () -> authService.refresh(registered.refreshToken()));
    }

    @Test
    void logoutRevokesTheRefreshToken() {
        var registered = authService.register(registerRequest("08012345681", "password123", null));

        authService.logout(registered.refreshToken());

        assertThrows(InvalidRefreshTokenException.class, () -> authService.refresh(registered.refreshToken()));
    }

    @Test
    void logoutWithUnknownTokenSucceedsSilently() {
        authService.logout("not-a-real-token");
    }

    // ---- Stored user

    @Test
    void registeredUserStartsWithPhoneNotVerified() {
        var tokens = authService.register(registerRequest("08012346001", "password123", null));

        var saved = userRepository.findById(tokens.user().getId()).orElseThrow();
        assertThat(saved.isPhoneVerified()).isFalse();
    }

    @Test
    void storedPasswordIsABcryptHashOfTheRawPassword() {
        var tokens = authService.register(registerRequest("08012346002", "correct-horse-9", null));

        var hash = userRepository.findById(tokens.user().getId()).orElseThrow().getPasswordHash();
        assertThat(hash).startsWith("$2").doesNotContain("correct-horse-9");
        assertThat(passwordEncoder.matches("correct-horse-9", hash)).isTrue();
    }

    // ---- Email normalisation

    @Test
    void emailIsTrimmedAndLowercasedBeforeSaving() {
        var tokens = authService.register(registerRequest("08012346003", "password123", "  Mixed.Case@Example.COM "));

        assertThat(userRepository.findById(tokens.user().getId()).orElseThrow().getEmail())
                .isEqualTo("mixed.case@example.com");
    }

    @Test
    void emailsDifferingOnlyByCaseAreTheSameAddress() {
        authService.register(registerRequest("08012346004", "password123", "Ada.Service@X.com"));

        assertThrows(DuplicateEmailException.class,
                () -> authService.register(registerRequest("08012346005", "password123", "ada.service@x.com")));
    }

    @Test
    void usersWithoutAnEmailDoNotCollide() {
        var first = authService.register(registerRequest("08012346006", "password123", null));
        var second = authService.register(registerRequest("08012346007", "password123", "   "));

        assertThat(userRepository.findById(first.user().getId()).orElseThrow().getEmail()).isNull();
        assertThat(userRepository.findById(second.user().getId()).orElseThrow().getEmail()).isNull();
    }

    private RegisterRequest registerRequest(String phone, String password, String email) {
        return new RegisterRequest(phone, password, "Test User", email);
    }
}
