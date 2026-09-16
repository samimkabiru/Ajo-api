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

    private RegisterRequest registerRequest(String phone, String password, String email) {
        return new RegisterRequest(phone, password, "Test User", email);
    }
}
